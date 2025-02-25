/*
 * Copyright 2023 David Crosson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.lmdb

import java.io.File
import zio.*

case class LMDBConfig(
  databasePath: File,
  mapSize: Long = 100_000_000_000L,
  maxCollections: Int = 10_000,
  maxReaders: Int = 100,
  fileSystemSynchronized: Boolean = true
)

object LMDBConfig {

  /** Create a LMDBConfig without pain using the database Path .lmdb/name within $HOME or current directory if HOME is unset
    * @param databaseName
    *   database name which will be used as the destination directory name for storage purposes
    * @param fileSystemSynchronized
    * @return
    *   a LMDB config
    */
  def build(
    databaseName: String = "default",
    fileSystemSynchronized: Boolean = true
  ): IO[Exception, LMDBConfig] = {
    for {
      home             <- System.envOrElse("HOME", ".")
      lmdbDatabasesHome = File(home, ".lmdb")
      databasePath      = File(lmdbDatabasesHome, databaseName)
      _                <- ZIO.attemptBlockingIO(databasePath.mkdirs())
      config            = LMDBConfig(databasePath, fileSystemSynchronized = fileSystemSynchronized)
    } yield config
  }

  /** Build a lmdb config layer
    *
    * @param databaseName
    *   database name which will be used as the destination directory name for storage purposes
    * @param fileSystemSynchronized
    * @return
    *   a LMDB config layer
    */
  def buildLayer(databaseName: String = "default", fileSystemSynchronized: Boolean = true): ULayer[LMDBConfig] = {
    ZLayer.fromZIO(build(databaseName, fileSystemSynchronized)).orDie
  }

}
