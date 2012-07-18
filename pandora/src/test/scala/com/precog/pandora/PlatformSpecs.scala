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
package com.precog
package pandora

import common.VectorCase
import common.kafka._
import common.security._

import daze._
import daze.util._

import pandora._

import quirrel._
import quirrel.emitter._
import quirrel.parser._
import quirrel.typer._

import yggdrasil._
import yggdrasil.actor._
import yggdrasil.leveldb._
import yggdrasil.metadata._
import yggdrasil.memoization._
import yggdrasil.serialization._
import yggdrasil.table._
import muspelheim._

import com.precog.util.FilesystemFileOps

import org.specs2.mutable._
  
import akka.actor.ActorSystem
import akka.dispatch.Await
import akka.dispatch.ExecutionContext
import akka.util.duration._

import java.io.File

import scalaz._
import scalaz.effect.IO

import org.streum.configrity.Configuration
import org.streum.configrity.io.BlockFormat

class PlatformSpecs 
    extends ParseEvalStackSpecs 
    with BlockStoreColumnarTableModule 
    with LevelDBProjectionModule 
    with SystemActorStorageModule 
    with StandaloneShardSystemActorModule { platformSpecs =>

  class YggConfig extends ParseEvalStackSpecConfig with StandaloneShardSystemConfig 
  object yggConfig  extends YggConfig

  class Storage extends SystemActorStorageLike(FileMetadataStorage.load(yggConfig.dataDir, FilesystemFileOps).unsafePerformIO) {
    val accessControl = new UnlimitedAccessControl()(asyncContext)
  }

  val storage = new Storage

  object Projection extends LevelDBProjectionCompanion {
    val fileOps = FilesystemFileOps
    def baseDir(descriptor: ProjectionDescriptor) = sys.error("todo")
  }

  override def startup() {
    // start storage shard 
    Await.result(storage.start(), controlTimeout)
  }
  
  override def shutdown() {
    // stop storage shard
    Await.result(storage.stop(), controlTimeout)
    
    actorSystem.shutdown()
  }
}
