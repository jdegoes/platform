/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.yggdrasil
package vfs

import ResourceError._
import table.Slice
import metadata.PathMetadata
import metadata.PathStructure

import com.precog.common._
import com.precog.common.accounts.AccountId
import com.precog.common.ingest._
import com.precog.common.security._
import com.precog.common.jobs._
import com.precog.niflheim._
import com.precog.yggdrasil.actor.IngestData
import com.precog.yggdrasil.nihdb.NIHDBProjection
import com.precog.yggdrasil.table.ColumnarTableModule
import com.precog.util._

import akka.actor.{Actor, ActorRef, ActorSystem, Props, ReceiveTimeout}
import akka.dispatch._
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.{Timeout, Duration}

import blueeyes.bkka.FutureMonad
import blueeyes.core.http.MimeType
import blueeyes.core.http.MimeTypes
import blueeyes.json._
import blueeyes.json.serialization._
import blueeyes.json.serialization.DefaultSerialization._
import blueeyes.util.Clock

import java.util.UUID
import java.io.{File, IOException, FileInputStream, FileOutputStream}
import java.nio.CharBuffer
import java.util.concurrent.ScheduledThreadPoolExecutor

import com.weiglewilczek.slf4s.Logging
import com.google.common.util.concurrent.ThreadFactoryBuilder

import scala.annotation.tailrec
import scalaz._
import scalaz.NonEmptyList._
import scalaz.EitherT._
import scalaz.effect.IO
import scalaz.std.list._
import scalaz.std.stream._
import scalaz.std.option._
import scalaz.std.map._
import scalaz.std.set._
import scalaz.std.tuple._

import scalaz.syntax.monad._
import scalaz.syntax.semigroup._
import scalaz.syntax.show._
import scalaz.syntax.traverse._
import scalaz.syntax.std.boolean._
import scalaz.syntax.std.option._
import scalaz.syntax.std.list._
import scalaz.syntax.effect.id._

sealed trait PathActionResponse
sealed trait ReadResult extends PathActionResponse
sealed trait WriteResult extends PathActionResponse
sealed trait MetadataResult extends PathActionResponse

case class UpdateSuccess(path: Path) extends WriteResult
case class PathChildren(path: Path, children: Set[PathMetadata]) extends MetadataResult
case class PathOpFailure(path: Path, error: ResourceError) extends ReadResult with WriteResult with MetadataResult

trait ActorVFSModule extends VFSModule[Future, Slice] {
  type Projection = NIHDBProjection

  def permissionsFinder: PermissionsFinder[Future]
  def jobManager: JobManager[Future]
  def resourceBuilder: ResourceBuilder

  case class ReadSuccess(path: Path, resource: Resource) extends ReadResult

  /**
   * Used to access resources. This is needed because opening a NIHDB requires
   * more than just a basedir, but also things like the chef, txLogScheduler, etc.
   * This also goes for blobs, where the metadata log requires the txLogScheduler.
   */
  class ResourceBuilder(
    actorSystem: ActorSystem,
    clock: Clock,
    chef: ActorRef,
    cookThreshold: Int,
    storageTimeout: Timeout,
    txLogSchedulerSize: Int = 20) extends Logging { // default for now, should come from config in the future

    private final val txLogScheduler = new ScheduledThreadPoolExecutor(txLogSchedulerSize,
      new ThreadFactoryBuilder().setNameFormat("HOWL-sched-%03d").build())

    private implicit val futureMonad = new FutureMonad(actorSystem.dispatcher)

    private def ensureDescriptorDir(versionDir: File): IO[File] = IO {
      if (versionDir.isDirectory || versionDir.mkdirs) versionDir
      else throw new IOException("Failed to create directory for projection: %s".format(versionDir))
    }

    // Resource creation/open and discovery
    def createNIHDB(versionDir: File, authorities: Authorities): IO[ResourceError \/ NIHDBResource] = {
      for {
        nihDir <- ensureDescriptorDir(versionDir)
        nihdbV <- NIHDB.create(chef, authorities, nihDir, cookThreshold, storageTimeout, txLogScheduler)(actorSystem)
      } yield {
        nihdbV.disjunction leftMap {
          ResourceError.fromExtractorError("Failed to create NIHDB in %s as %s".format(versionDir.toString, authorities))
        } map {
          NIHDBResource(_)
        }
      }
    }

    def openNIHDB(descriptorDir: File): IO[ResourceError \/ NIHDBResource] = {
      NIHDB.open(chef, descriptorDir, cookThreshold, storageTimeout, txLogScheduler)(actorSystem) map {
        _ map {
          _.disjunction map { NIHDBResource(_) } leftMap {
            ResourceError.fromExtractorError("Failed to open NIHDB from %s".format(descriptorDir.toString))
          }
        } getOrElse {
          \/.left(NotFound("No NIHDB projection found in %s".format(descriptorDir)))
        }
      }
    }

    final val blobMetadataFilename = "blob_metadata"

    def isBlob(versionDir: File): Boolean = (new File(versionDir, blobMetadataFilename)).exists

    /**
     * Open the blob for reading in `baseDir`.
     */
    def openBlob(versionDir: File): IO[ResourceError \/ FileBlobResource] = IO {
      //val metadataStore = PersistentJValue(versionDir, blobMetadataFilename)
      //val metadata = metadataStore.json.validated[BlobMetadata]
      JParser.parseFromFile(new File(versionDir, blobMetadataFilename)).leftMap(Extractor.Error.thrown).
      flatMap(_.validated[BlobMetadata]).
      disjunction.map { metadata =>
        FileBlobResource(new File(versionDir, "data"), metadata) //(actorSystem.dispatcher)
      } leftMap {
        ResourceError.fromExtractorError("Error reading metadata from versionDir %s".format(versionDir.toString))
      }
    }

    /**
     * Creates a blob from a data stream.
     */
    def createBlob[M[+_]](versionDir: File, mimeType: MimeType, authorities: Authorities, data: StreamT[M, Array[Byte]])(implicit M: Monad[M], IOT: IO ~> M): M[ResourceError \/ FileBlobResource] = {
      def write(out: FileOutputStream, size: Long, stream: StreamT[M, Array[Byte]]): M[ResourceError \/ Long] = {
        stream.uncons.flatMap {
          case Some((bytes, tail)) =>
            try {
              out.write(bytes)
              write(out, size + bytes.length, tail)
            } catch {
              case (ioe: IOException) =>
                out.close()
                \/.left(IOError(ioe)).point[M]
            }

          case None =>
            out.close()
            \/.right(size).point[M]
        }
      }

      for {
        _ <- IOT { IOUtils.makeDirectory(versionDir) }
        file = (new File(versionDir, "data"))
        _ = logger.debug("Creating new blob at " + file)
        writeResult <- write(new FileOutputStream(file), 0L, data)
        blobResult <- IOT {
          writeResult traverse { size =>
            logger.debug("Write complete on " + file)
            val metadata = BlobMetadata(mimeType, size, clock.now(), authorities)
            //val metadataStore = PersistentJValue(versionDir, blobMetadataFilename)
            //metadataStore.json = metadata.serialize
            IOUtils.writeToFile(metadata.serialize.renderCompact, new File(versionDir, blobMetadataFilename)) map { _ =>
              FileBlobResource(file, metadata)
            }
          }
        }
      } yield blobResult
    }
  }


  case class NIHDBResource(val db: NIHDB) extends ProjectionResource with Logging {
    val mimeType: MimeType = FileContent.XQuirrelData

    def authorities = db.authorities

    def append(batch: NIHDB.Batch): IO[PrecogUnit] = db.insert(Seq(batch))

    def projection(implicit M: Monad[Future]) = NIHDBProjection.wrap(db)

    def recordCount(implicit M: Monad[Future]) = projection.map(_.length)

    def asByteStream(mimeType: MimeType)(implicit M: Monad[Future]) = OptionT {
      projection map { p =>
        val slices = p.getBlockStream(None) map VFS.derefValue
        ColumnarTableModule.byteStream(slices, Some(mimeType))
      }
    }
  }

  object FileBlobResource {
    val ChunkSize = 100 * 1024

    def IOF(implicit M: Monad[Future]): IO ~> Future = new NaturalTransformation[IO, Future] {
      def apply[A](io: IO[A]) = M.point(io.unsafePerformIO)
    }
  }

  /**
   * A blob of data that has been persisted to disk.
   */
  final case class FileBlobResource(dataFile: File, metadata: BlobMetadata) extends BlobResource {
    import FileContent._
    import FileBlobResource._

    val authorities: Authorities = metadata.authorities
    val mimeType: MimeType = metadata.mimeType
    val byteLength = metadata.size

    /** Suck the file into a String */
    def asString(implicit M: Monad[Future]): OptionT[Future, String] = OptionT(M point {
      stringTypes.contains(mimeType).option(IOUtils.readFileToString(dataFile)).sequence.unsafePerformIO
    })

    /** Stream the file off disk. */
    def ioStream: StreamT[IO, Array[Byte]] = {
      @tailrec
      def readChunk(fin: FileInputStream, skip: Long): Option[Array[Byte]] = {
        val remaining = skip - fin.skip(skip)
        if (remaining == 0) {
          val bytes = new Array[Byte](ChunkSize)
          val read = fin.read(bytes)

          if (read < 0) None
          else if (read == bytes.length) Some(bytes)
          else Some(java.util.Arrays.copyOf(bytes, read))
        } else {
          readChunk(fin, remaining)
        }
      }

      StreamT.unfoldM[IO, Array[Byte], Long](0L) { offset =>
        IO(new FileInputStream(dataFile)).bracket(f => IO(f.close())) { in =>
          IO(readChunk(in, offset) map { bytes =>
            (bytes, offset + bytes.length)
          })
        }
      }
    }

    override def fold[A](blobResource: BlobResource => A, projectionResource: ProjectionResource => A) = blobResource(this)

    def asByteStream(mimeType: MimeType)(implicit M: Monad[Future]) = OptionT {
      M.point {
        Some(ioStream.trans(IOF))
      }
    }
  }

  class VFSCompanion extends VFSCompanionLike {
    def toJsonElements(slice: Slice) = slice.toJsonElements
    def derefValue(slice: Slice) = slice.deref(TransSpecModule.paths.Value)
    def blockSize(slice: Slice) = slice.size
    def pathStructure(selector: CPath)(implicit M: Monad[Future]) = { (projection: Projection) =>
      right {
        for (children <- projection.structure) yield {
          PathStructure(projection.reduce(Reductions.count, selector), children.map(_.selector))
        }
      }
    }
  }

  object VFS extends VFSCompanion

  class ActorVFS(projectionsActor: ActorRef, projectionReadTimeout: Timeout, sliceIngestTimeout: Timeout)(implicit M: Monad[Future]) extends VFS {

    def writeAll(data: Seq[(Long, EventMessage)]): IO[PrecogUnit] = {
      IO { projectionsActor ! IngestData(data) }
    }

    def writeAllSync(data: Seq[(Long, EventMessage)]): EitherT[Future, ResourceError, PrecogUnit] = EitherT {
      implicit val timeout = sliceIngestTimeout
      for {
        // it's necessary to group by path then traverse since each path will respond to ingest independently.
        // -- a bit of a leak of implementation detail, but that's the actor model for you.
        allResults <- (data groupBy { case (offset, msg) => msg.path }).toStream traverse { case (path, subset) =>
          (projectionsActor ? IngestData(subset)).mapTo[WriteResult]
        }
      } yield {
        val errors: List[ResourceError] = allResults.toList collect { case PathOpFailure(_, error) => error }
        errors.toNel.map(ResourceError.all).toLeftDisjunction(PrecogUnit)
      }
    }

    def readResource(path: Path, version: Version): EitherT[Future, ResourceError, Resource] = {
      implicit val t = projectionReadTimeout
      EitherT {
        (projectionsActor ? Read(path, version)).mapTo[ReadResult] map {
          case ReadSuccess(_, resource) => \/.right(resource)
          case PathOpFailure(_, error) => \/.left(error)
        }
      }
    }

    def findDirectChildren(path: Path): EitherT[Future, ResourceError, Set[PathMetadata]] = {
      implicit val t = projectionReadTimeout
      EitherT {
        (projectionsActor ? FindChildren(path)).mapTo[MetadataResult] map {
          case PathChildren(_, children) => \/.right(for (pm <- children; p0 <- (pm.path - path)) yield { pm.copy(path = p0) })
          case PathOpFailure(_, error) => \/.left(error)
        }
      }
    }

    def findPathMetadata(path: Path): EitherT[Future, ResourceError, PathMetadata] = {
      implicit val t = projectionReadTimeout
      EitherT {
        (projectionsActor ? FindPathMetadata(path)).mapTo[MetadataResult] map {
          case PathChildren(_, children) => 
            children.headOption flatMap { pm => 
              (pm.path - path) map { p0 => pm.copy(path = p0) } 
            } toRightDisjunction {
              ResourceError.notFound("Cannot return metadata for path %s".format(path.path))
            }
          case PathOpFailure(_, error) => 
            \/.left(error)
        }
      }
    }


    def currentVersion(path: Path) = {
      implicit val t = projectionReadTimeout
      (projectionsActor ? CurrentVersion(path)).mapTo[Option[VersionEntry]]
    }
  }

  case class IngestBundle(data: Seq[(Long, EventMessage)], perms: Map[APIKey, Set[WritePermission]])

  class PathRoutingActor(baseDir: File, shutdownTimeout: Duration, quiescenceTimeout: Duration, maxOpenPaths: Int, clock: Clock) extends Actor with Logging {
    import com.precog.util.cache._
    import com.precog.util.cache.Cache._
    import com.google.common.cache.RemovalCause

    private implicit val M: Monad[Future] = new FutureMonad(context.dispatcher)

    private[this] val pathLRU = Cache.simple[Path, Unit](
      MaxSize(maxOpenPaths), 
      OnRemoval({(p: Path, _: Unit, _: RemovalCause) => pathActors.get(p).foreach(_ ! ReceiveTimeout) })
    )

    private[this] var pathActors = Map.empty[Path, ActorRef]

    override def postStop = {
      logger.info("Shutdown of path actors complete")
    }

    private[this] def targetActor(path: Path): IO[ActorRef] = {
      pathActors.get(path).map(IO(_)) getOrElse {
        val pathDir = VFSPathUtils.pathDir(baseDir, path)

        for {
          _ <- IOUtils.makeDirectory(pathDir)
          _ = logger.debug("Created new path dir for %s : %s".format(path, pathDir))
          vlog <- VersionLog.open(pathDir)
          actorV <- vlog traverse { versionLog =>
            logger.debug("Creating new PathManagerActor for " + path)
            context.actorOf(Props(new PathManagerActor(path, VFSPathUtils.versionsSubdir(pathDir), versionLog, shutdownTimeout, quiescenceTimeout, clock, self))) tap { newActor =>
              IO { pathActors += (path -> newActor); pathLRU += (path -> ()) }
            }
          }
        } yield {
          actorV valueOr {
            case Extractor.Thrown(t) => throw t
            case error => throw new Exception(error.message)
          }
        }
      }
    }

    def receive = {
      case FindChildren(path) =>
        logger.debug("Received request to find children of %s".format(path.path))
        VFSPathUtils.findChildren(baseDir, path) map { children =>
          sender ! PathChildren(path, children)
        } except {
          case t: Throwable =>
            logger.error("Error obtaining path children for " + path, t)
            IO { sender ! PathOpFailure(path, IOError(t)) }
        } unsafePerformIO

      case FindPathMetadata(path) =>
        logger.debug("Received request to find metadata for path %s".format(path.path))
        val requestor = sender
        val eio = VFSPathUtils.currentPathMetadata(baseDir, path) map { pathMetadata =>
          requestor ! PathChildren(path, Set(pathMetadata))
        } leftMap { error =>
          requestor ! PathOpFailure(path, error)
        }

        eio.run.unsafePerformIO

      case op: PathOp =>
        val requestor = sender
        val io = targetActor(op.path) map { _.tell(op, requestor) } except {
          case t: Throwable =>
            logger.error("Error obtaining path actor for " + op.path, t)
            IO { requestor ! PathOpFailure(op.path, IOError(t)) }
        }

        io.unsafePerformIO

      case IngestData(messages) =>
        logger.debug("Received %d messages for ingest".format(messages.size))
        val requestor = sender
        val groupedAndPermissioned = messages.groupBy({ case (_, event) => event.path }).toStream traverse {
          case (path, pathMessages) =>
            targetActor(path) map { pathActor =>
              pathMessages.map(_._2.apiKey).distinct.toStream traverse { apiKey =>
                permissionsFinder.writePermissions(apiKey, path, clock.instant()) map { apiKey -> _ }
              } map { perms =>
                val allPerms: Map[APIKey, Set[WritePermission]] = perms.map(Map(_)).suml
                val (totalArchives, totalEvents, totalStoreFiles) = pathMessages.foldLeft((0, 0, 0)) {
                  case ((archived, events, storeFiles), (_, IngestMessage(_, _, _, data, _, _, _))) => (archived, events + data.size, storeFiles)
                  case ((archived, events, storeFiles), (_, am: ArchiveMessage)) => (archived + 1, events, storeFiles)
                  case ((archived, events, storeFiles), (_, sf: StoreFileMessage)) => (archived, events, storeFiles + 1)
                }
                logger.debug("Sending %d archives, %d storeFiles, and %d events to %s".format(totalArchives, totalStoreFiles, totalEvents, path))
                pathActor.tell(IngestBundle(pathMessages, allPerms), requestor)
              }
            } except {
              case t: Throwable => IO(logger.error("Failure during version log open on " + path, t))
            }
        }

        groupedAndPermissioned.unsafePerformIO
    }
  }

  /**
    * An actor that manages resources under a given path. The baseDir is the version
    * subdir for the path.
    */
  final class PathManagerActor(path: Path, baseDir: File, versionLog: VersionLog, shutdownTimeout: Duration, quiescenceTimeout: Duration, clock: Clock, routingActor: ActorRef) extends Actor with Logging {
    context.setReceiveTimeout(quiescenceTimeout)

    private[this] implicit def executor: ExecutionContext = context.dispatcher
    private[this] implicit val futureM = new FutureMonad(executor)

    // Keeps track of the resources for a given version/authority pair
    // TODO: make this an LRU cache
    private[this] var versions = Map[UUID, Resource]()

    override def postStop = {
      val closeAll = versions.values.toStream traverse {
        case NIHDBResource(db) => db.close(context.system)
        case _ => Promise successful PrecogUnit
      }

      Await.result(closeAll, shutdownTimeout)
      versionLog.close
      logger.info("Shutdown of path actor %s complete".format(path))
    }

    private def versionDir(version: UUID) = new File(baseDir, version.toString)

    private def canCreate(path: Path, permissions: Set[WritePermission], authorities: Authorities): Boolean = {
      logger.trace("Checking write permission for " + path + " as " + authorities + " among " + permissions)
      PermissionsFinder.canWriteAs(permissions filter { _.path.isEqualOrParentOf(path) }, authorities)
    }

    private def promoteVersion(version: UUID): IO[PrecogUnit] = {
      // we only promote if the requested version is in progress
      if (versionLog.isCompleted(version)) {
        IO(PrecogUnit)
      } else {
        versionLog.completeVersion(version)
      }
    }

    private def openResource(version: UUID): EitherT[IO, ResourceError, Resource] = {
      versions.get(version) map { r =>
        logger.debug("Located existing resource for " + version)
        right(IO(r))
      } getOrElse {
        logger.debug("Opening new resource for " + version)
        versionLog.find(version) map {
          case VersionEntry(v, _, _) =>
            val dir = versionDir(v)
            val openf = if (NIHDB.hasProjection(dir)) { resourceBuilder.openNIHDB _ }
                        else { resourceBuilder.openBlob _ }

            for {
              resource <- EitherT {
                openf(dir) flatMap {
                  _ tap { resourceV =>
                    IO(resourceV foreach { r => versions += (version -> r) })
                  }
                }
              }
            } yield resource
        } getOrElse {
          left(IO(Corrupt("No version %s found to exist for resource %s.".format(version, path.path))))
        }
      }
    }

    private def performCreate(apiKey: APIKey, data: PathData, version: UUID, writeAs: Authorities, complete: Boolean): IO[PathActionResponse] = {
      implicit val ioId = NaturalTransformation.refl[IO]
      for {
        _ <- versionLog.addVersion(VersionEntry(version, data.typeName, clock.instant()))
        created <- data match {
          case BlobData(bytes, mimeType) =>
            resourceBuilder.createBlob[IO](versionDir(version), mimeType, writeAs, bytes :: StreamT.empty[IO, Array[Byte]])

          case NIHDBData(data) =>
            resourceBuilder.createNIHDB(versionDir(version), writeAs) flatMap {
              _ traverse { nihdbr =>
                nihdbr tap { _.db.insert(data) }
              }
            }
        }
        _ <- created traverse { resource =>
          for {
            _ <- IO { versions += (version -> resource) }
            _ <- complete.whenM(versionLog.completeVersion(version) >> versionLog.setHead(version) >> maybeExpireCache(apiKey, resource))
          } yield PrecogUnit
        }
      } yield {
        created.fold(
          error => PathOpFailure(path, error),
          (_: Resource) => UpdateSuccess(path)
        )
      }
    }

    private def maybeExpireCache(apiKey: APIKey, resource: Resource): IO[PrecogUnit] = {
      resource.fold(
        blobr => IO {
          if (blobr.mimeType == FileContent.XQuirrelScript) {
            // invalidate the cache
            val cachePath = path / Path(".cached") //TODO: factor out this logic
            //FIXME: remove eventId from archive messages?
            routingActor ! ArchiveMessage(apiKey, cachePath, None, EventId.fromLong(0l), clock.instant())
          }
        },
        nihdbr => IO(PrecogUnit)
      )
    }

    private def maybeCompleteJob(msg: EventMessage, terminal: Boolean, response: PathActionResponse) = {
      //TODO: Add job progress updates
      (response == UpdateSuccess(msg.path) && terminal).option(msg.jobId).join traverse { jobManager.finish(_, clock.now()) } map { _ => response }
    }

    def processEventMessages(msgs: Stream[(Long, EventMessage)], permissions: Map[APIKey, Set[WritePermission]], requestor: ActorRef): IO[PrecogUnit] = {
      logger.debug("About to persist %d messages; replying to %s".format(msgs.size, requestor.toString))

      def openNIHDB(version: UUID): EitherT[IO, ResourceError, ProjectionResource] = {
        openResource(version) flatMap {
          _.fold(
            blob => left(IO(NotFound("Located resource on %s is a BLOB, not a projection" format path.path))),
            db => right(IO(db))
          )
        }
      }

      def persistNIHDB(createIfAbsent: Boolean, offset: Long, msg: IngestMessage, streamId: UUID, terminal: Boolean): IO[PrecogUnit] = {
        def batch(msg: IngestMessage) = NIHDB.Batch(offset, msg.data.map(_.value))

        if (versionLog.find(streamId).isDefined) {
          openNIHDB(streamId).fold[IO[PrecogUnit]](
            error => IO(requestor ! PathOpFailure(path, error)),
            resource => for {
              _ <- resource.append(batch(msg))
              // FIXME: completeVersion and setHead should be one op
              _ <- terminal.whenM(versionLog.completeVersion(streamId) >> versionLog.setHead(streamId))
            } yield {
              logger.trace("Sent insert message for " + msg + " to nihdb")
              // FIXME: We aren't actually guaranteed success here because NIHDB might do something screwy.
              maybeCompleteJob(msg, terminal, UpdateSuccess(msg.path)) pipeTo requestor
              PrecogUnit
            }
          ).join
        } else if (createIfAbsent) {
            logger.trace("Creating new nihdb database for streamId " + streamId)
            performCreate(msg.apiKey, NIHDBData(List(batch(msg))), streamId, msg.writeAs, terminal) map { response =>
              maybeCompleteJob(msg, terminal, response) pipeTo requestor
              PrecogUnit
            }
        } else {
          //TODO: update job
          logger.warn("Cannot create new database for " + streamId)
          IO(requestor ! PathOpFailure(path, IllegalWriteRequestError("Cannot create new resource. %s not applied.".format(msg.toString))))
        }
      }

      def persistFile(createIfAbsent: Boolean, offset: Long, msg: StoreFileMessage, streamId: UUID, terminal: Boolean): IO[PrecogUnit] = {
        logger.debug("Persisting file on %s for offset %d".format(path, offset))
        // TODO: I think the semantics here of createIfAbsent aren't
        // quite right. If we're in a replay we don't want to return
        // errors if we're already complete
        if (createIfAbsent) {
          performCreate(msg.apiKey, BlobData(msg.content.data, msg.content.mimeType), streamId, msg.writeAs, terminal) map { response =>
            maybeCompleteJob(msg, terminal, response) pipeTo requestor
            PrecogUnit
          }
        } else {
          //TODO: update job
          IO(requestor ! PathOpFailure(path, IllegalWriteRequestError("Cannot overwrite existing resource. %s not applied.".format(msg.toString))))
        }
      }

      msgs traverse {
        case (offset, msg @ IngestMessage(apiKey, path, _, _, _, _, streamRef)) =>
          streamRef match {
            case StreamRef.Create(streamId, terminal) =>
              logger.trace("Received create for %s stream %s: current: %b, complete: %b".format(path.path, streamId, versionLog.current.isEmpty, versionLog.isCompleted(streamId)))
              persistNIHDB(versionLog.current.isEmpty && !versionLog.isCompleted(streamId), offset, msg, streamId, terminal)

            case StreamRef.Replace(streamId, terminal) =>
              logger.trace("Received replace for %s stream %s: complete: %b".format(path.path, streamId, versionLog.isCompleted(streamId)))
              persistNIHDB(!versionLog.isCompleted(streamId), offset, msg, streamId, terminal)

            case StreamRef.Append =>
              logger.trace("Received append for %s".format(path.path))
              val streamId = versionLog.current.map(_.id).getOrElse(UUID.randomUUID())
              for {
                _ <- persistNIHDB(canCreate(msg.path, permissions(apiKey), msg.writeAs), offset, msg, streamId, false)
                _ <- versionLog.completeVersion(streamId) >> versionLog.setHead(streamId)
              } yield PrecogUnit
          }

        case (offset, msg @ StoreFileMessage(_, path, _, _, _, _, _, streamRef)) =>
          streamRef match {
            case StreamRef.Create(streamId, terminal) =>
              if (! terminal) {
                logger.warn("Non-terminal BLOB for %s will not currently behave correctly!".format(path))
              }
              persistFile(versionLog.current.isEmpty && !versionLog.isCompleted(streamId), offset, msg, streamId, terminal)

            case StreamRef.Replace(streamId, terminal) =>
              if (! terminal) {
                logger.warn("Non-terminal BLOB for %s will not currently behave correctly!".format(path))
              }
              persistFile(!versionLog.isCompleted(streamId), offset, msg, streamId, terminal)

            case StreamRef.Append =>
              IO(requestor ! PathOpFailure(path, IllegalWriteRequestError("Append is not yet supported for binary files.")))
          }

        case (offset, ArchiveMessage(apiKey, path, jobId, _, timestamp)) =>
          versionLog.clearHead >> IO(requestor ! UpdateSuccess(path))
      } map {
        _ => PrecogUnit
      }
    }

    def versionOpt(version: Version) = version match {
      case Version.Archived(id) => Some(id)
      case Version.Current => versionLog.current.map(_.id)
    }

    def receive = {
      case ReceiveTimeout =>
        logger.info("Resource entering state of quiescence after receive timeout.")
        val quiesce = versions.values.toStream collect { case NIHDBResource(db) => db } traverse (_.quiesce)
        quiesce.unsafePerformIO

      case IngestBundle(messages, permissions) =>
        logger.debug("Received ingest request for %d messages.".format(messages.size))
        processEventMessages(messages.toStream, permissions, sender).unsafePerformIO

      case msg @ Read(_, version) =>
        logger.debug("Received Read request " + msg)

        val requestor = sender
        val io: IO[ReadResult] = version match {
          case Version.Current =>
            versionLog.current map { v =>
              openResource(v.id).fold(PathOpFailure(path, _), ReadSuccess(path, _))
            } getOrElse {
              IO(PathOpFailure(path, NotFound("No current version found for path %s".format(path.path))))
            }

          case Version.Archived(id) =>
            openResource(id).fold(PathOpFailure(path, _), ReadSuccess(path, _))
        }

        io.map(requestor ! _).unsafePerformIO

      case CurrentVersion(_) =>
        sender ! versionLog.current
    }
  }
}
