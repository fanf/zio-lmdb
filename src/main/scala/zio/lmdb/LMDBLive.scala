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

import zio.*
import zio.stm.*
import zio.json.*
import zio.stream.*

import java.io.File
import org.lmdbjava.{Cursor, Dbi, DbiFlags, Env, EnvFlags, KeyRange, Txn, Verifier}
import org.lmdbjava.SeekOp.*
import org.lmdbjava.CursorIterable.KeyVal

import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import zio.lmdb.StorageUserError.*
import zio.lmdb.StorageSystemError.*

/** LMDB ZIO abstraction layer, provides standard atomic operations implementations
  * @param env
  * @param openedCollectionDbisRef
  * @param reentrantLock
  */
class LMDBLive(
  env: Env[ByteBuffer],
  openedCollectionDbisRef: Ref[Map[String, Dbi[ByteBuffer]]],
  reentrantLock: TReentrantLock
) extends LMDB {
  val charset = StandardCharsets.UTF_8

  private def makeKeyByteBuffer(id: String): IO[OverSizedKey | StorageSystemError, ByteBuffer] = {
    val keyBytes = id.getBytes(charset)
    if (keyBytes.length > env.getMaxKeySize) ZIO.fail(OverSizedKey(id, keyBytes.length, env.getMaxKeySize))
    else
      for {
        key <- ZIO.attempt(ByteBuffer.allocateDirect(env.getMaxKeySize)).mapError(err => InternalError("Couldn't allocate byte buffer for key", Some(err)))
        _   <- ZIO.attempt(key.put(keyBytes).flip).mapError(err => InternalError("Couldn't copy key bytes to buffer", Some(err)))
      } yield key
  }

  /** return an existing collection
    * @param name
    * @return
    */
  private def getCollectionDbi(name: CollectionName): IO[CollectionNotFound, Dbi[ByteBuffer]] = {
    val alreadyHereLogic = for {
      openedCollectionDbis <- openedCollectionDbisRef.get
    } yield openedCollectionDbis.get(name)

    val openAndRememberLogic = for {
      openedCollectionDbis <- reentrantLock.withWriteLock( // See https://github.com/lmdbjava/lmdbjava/issues/195
                                openedCollectionDbisRef.updateAndGet(before =>
                                  if (before.contains(name)) before
                                  else before + (name -> env.openDbi(name))
                                )
                              )
    } yield openedCollectionDbis.get(name)

    alreadyHereLogic.some
      .orElse(openAndRememberLogic.some)
      .mapError(err => CollectionNotFound(name))
  }

  /** check if a collection exists
    * @param name
    * @return
    */
  override def collectionExists(name: CollectionName): IO[StorageSystemError, Boolean] = {
    for {
      openedCollectionDbis <- openedCollectionDbisRef.get
      found                <- if (openedCollectionDbis.contains(name)) ZIO.succeed(true)
                              else collectionsAvailable().map(_.contains(name))
    } yield found
  }

  /** Get a collection
    *
    * @param name
    *   collection name
    * @return
    */
  override def collectionGet[T](name: CollectionName)(using JsonEncoder[T], JsonDecoder[T]): IO[StorageSystemError | CollectionNotFound, LMDBCollection[T]] = {
    for {
      exists     <- collectionExists(name)
      collection <- ZIO.cond[CollectionNotFound, LMDBCollection[T]](exists, LMDBCollection[T](name, this), CollectionNotFound(name))
    } yield collection
  }

  /** get collection current size
    *
    * @param name
    *   collection name
    * @return
    */
  override def collectionSize(name: CollectionName): IO[StorageSystemError | CollectionNotFound, Long] = {
    for {
      collectionDbi <- getCollectionDbi(name)
      stats         <- withReadTransaction(name) { txn =>
                         ZIO
                           .attempt(collectionDbi.stat(txn))
                           .mapError(err => InternalError(s"Couldn't get $name size", Some(err)))
                       }
    } yield stats.entries
  }

  /** create the collection
    * @param name
    * @return nothing
    */
  override def collectionAllocate(name: CollectionName): IO[CollectionAlreadExists | StorageSystemError, Unit] = {
    for {
      exists <- collectionExists(name)
      _      <- ZIO.cond[CollectionAlreadExists, Unit](!exists, (), CollectionAlreadExists(name))
      _      <- collectionCreateLogic(name)
    } yield ()
  }

  /** create the collection and returns a collection handler
    * @param name
    * @return
    */
  override def collectionCreate[T](name: CollectionName)(using JsonEncoder[T], JsonDecoder[T]): IO[CollectionAlreadExists | StorageSystemError, LMDBCollection[T]] = {
    collectionAllocate(name) *> ZIO.succeed(LMDBCollection[T](name, this))
  }

  private def collectionCreateLogic(name: CollectionName): ZIO[Any, StorageSystemError, Unit] = reentrantLock.withWriteLock {
    for {
      openedCollectionDbis <- reentrantLock.withWriteLock( // See https://github.com/lmdbjava/lmdbjava/issues/195
                                openedCollectionDbisRef.updateAndGet(before =>
                                  if (before.contains(name)) before
                                  else before + (name -> env.openDbi(name, DbiFlags.MDB_CREATE)) // TODO
                                )
                              )
      collectionDbi        <- ZIO
                                .from(openedCollectionDbis.get(name))
                                .mapError(err => InternalError(s"Couldn't create DB $name"))
    } yield ()
  }

  private def withWriteTransaction(colName: CollectionName): ZIO.Release[Any, StorageSystemError, Txn[ByteBuffer]] =
    ZIO.acquireReleaseWith(
      ZIO
        .attemptBlocking(env.txnWrite())
        .mapError(err => InternalError(s"Couldn't acquire write transaction on $colName", Some(err)))
    )(txn =>
      ZIO
        .attemptBlocking(txn.close())
        .ignoreLogged
    )

  private def withReadTransaction(colName: CollectionName): ZIO.Release[Any, StorageSystemError, Txn[ByteBuffer]] =
    ZIO.acquireReleaseWith(
      ZIO
        .attemptBlocking(env.txnRead())
        .mapError(err => InternalError(s"Couldn't acquire read transaction on $colName", Some(err)))
    )(txn =>
      ZIO
        .attemptBlocking(txn.close())
        .ignoreLogged
    )

  /** Remove all the content of a collection
    * @param colName
    * @return
    */
  override def collectionClear(colName: CollectionName): IO[CollectionNotFound | StorageSystemError, Unit] = {
    def collectionClearLogic(colDbi: Dbi[ByteBuffer]): ZIO[Any, StorageSystemError, Unit] = {
      reentrantLock.withWriteLock(
        withWriteTransaction(colName) { txn =>
          ZIO
            .attemptBlocking(colDbi.drop(txn))
            .mapError(err => InternalError(s"Couldn't clear $colName", Some(err)))
        }
      )
    }
    for {
      collectionDbi <- getCollectionDbi(colName)
      _             <- collectionClearLogic(collectionDbi)
    } yield ()
  }

  /** Check server current configuration compatibility
    */
  override def platformCheck(): IO[StorageSystemError, Unit] = reentrantLock.withWriteLock {
    ZIO
      .attemptBlockingIO(new Verifier(env).runFor(5, TimeUnit.SECONDS))
      .mapError(err => InternalError(err.getMessage, Some(err)))
      .unit
  }

  /** list collections
    */
  override def collectionsAvailable(): IO[StorageSystemError, List[CollectionName]] = {
    reentrantLock.withWriteLock( // See https://github.com/lmdbjava/lmdbjava/issues/195
      for {
        collectionNames <- ZIO
                             .attempt(
                               env
                                 .getDbiNames()
                                 .asScala
                                 .map(bytes => new String(bytes))
                                 .toList
                             )
                             .mapError(err => InternalError("Couldn't list collections", Some(err)))
      } yield collectionNames
    )
  }

  /** delete record
    * @param key
    * @return
    */
  override def delete[T](colName: CollectionName, key: RecordKey)(using JsonEncoder[T], JsonDecoder[T]): IO[DeleteErrors, Option[T]] = {
    def deleteLogic(colDbi: Dbi[ByteBuffer]): IO[DeleteErrors, Option[T]] = {
      reentrantLock.withWriteLock(
        withWriteTransaction(colName) { txn =>
          for {
            key           <- makeKeyByteBuffer(key)
            found         <- ZIO.attemptBlocking(Option(colDbi.get(txn, key))).mapError[DeleteErrors](err => InternalError(s"Couldn't fetch $key for delete on $colName", Some(err)))
            mayBeRawValue <- ZIO.foreach(found)(_ => ZIO.succeed(txn.`val`()))
            mayBeDoc      <- ZIO.foreach(mayBeRawValue) { rawValue =>
                               ZIO.fromEither(charset.decode(rawValue).fromJson[T]).mapError[DeleteErrors](msg => JsonFailure(msg))
                             }
            keyFound      <- ZIO.attemptBlocking(colDbi.delete(txn, key)).mapError[DeleteErrors](err => InternalError(s"Couldn't delete $key from $colName", Some(err)))
            _             <- ZIO.attemptBlocking(txn.commit()).mapError[DeleteErrors](err => InternalError("Couldn't commit transaction", Some(err)))
          } yield mayBeDoc
        }
      )
    }
    for {
      db     <- getCollectionDbi(colName)
      status <- deleteLogic(db)
    } yield status
  }

  /** fetch a record
    * @param key
    * @return
    */
  override def fetch[T](colName: CollectionName, key: RecordKey)(using JsonEncoder[T], JsonDecoder[T]): IO[FetchErrors, Option[T]] = {
    def fetchLogic(colDbi: Dbi[ByteBuffer]): ZIO[Any, FetchErrors, Option[T]] = {
      withReadTransaction(colName) { txn =>
        for {
          key           <- makeKeyByteBuffer(key)
          found         <- ZIO.attemptBlocking(Option(colDbi.get(txn, key))).mapError[FetchErrors](err => InternalError(s"Couldn't fetch $key on $colName", Some(err)))
          mayBeRawValue <- ZIO.foreach(found)(_ => ZIO.succeed(txn.`val`()))
          document      <- ZIO
                             .foreach(mayBeRawValue) { rawValue =>
                               ZIO.fromEither(charset.decode(rawValue).fromJson[T]).mapError[FetchErrors](msg => JsonFailure(msg))
                             }
        } yield document
      }
    }
    for {
      db     <- getCollectionDbi(colName)
      result <- fetchLogic(db)
    } yield result
  }

  /** overwrite or insert a document
    * @param key
    * @param document
    * @tparam T
    * @return
    */
  override def upsertOverwrite[T](colName: CollectionName, key: RecordKey, document: T)(using JsonEncoder[T], JsonDecoder[T]): IO[UpsertErrors, UpsertState[T]] = {
    upsert(colName, key, _ => document)
  }

  /** atomic document update/insert throw a lambda
    * @param key
    * @param modifier
    * @return
    */
  override def upsert[T](colName: CollectionName, key: RecordKey, modifier: Option[T] => T)(using JsonEncoder[T], JsonDecoder[T]): IO[UpsertErrors, UpsertState[T]] = {
    def upsertLogic(collectionDbi: Dbi[ByteBuffer]): IO[UpsertErrors, UpsertState[T]] = {
      reentrantLock.withWriteLock(
        withWriteTransaction(colName) { txn =>
          for {
            key            <- makeKeyByteBuffer(key)
            found          <- ZIO.attemptBlocking(Option(collectionDbi.get(txn, key))).mapError(err => InternalError(s"Couldn't fetch $key for upsert on $colName", Some(err)))
            mayBeRawValue  <- ZIO.foreach(found)(_ => ZIO.succeed(txn.`val`()))
            mayBeDocBefore <- ZIO.foreach(mayBeRawValue) { rawValue =>
                                ZIO.fromEither(charset.decode(rawValue).fromJson[T]).mapError[UpsertErrors](msg => JsonFailure(msg))
                              }
            docAfter        = modifier(mayBeDocBefore)
            jsonDocBytes    = docAfter.toJson.getBytes(charset)
            valueBuffer    <- ZIO.attemptBlocking(ByteBuffer.allocateDirect(jsonDocBytes.size)).mapError(err => InternalError("Couldn't allocate byte buffer for json value", Some(err)))
            _              <- ZIO.attemptBlocking(valueBuffer.put(jsonDocBytes).flip).mapError(err => InternalError("Couldn't copy value bytes to buffer", Some(err)))
            _              <- ZIO.attemptBlocking(collectionDbi.put(txn, key, valueBuffer)).mapError(err => InternalError(s"Couldn't upsert $key into $colName", Some(err)))
            _              <- ZIO.attemptBlocking(txn.commit()).mapError(err => InternalError(s"Couldn't commit upsertOverwrite $key into $colName", Some(err)))
          } yield UpsertState(previous = mayBeDocBefore, current = docAfter)
        }
      )
    }
    for {
      collectionDbi <- getCollectionDbi(colName)
      result        <- upsertLogic(collectionDbi)
    } yield result
  }

  /** Dangerous collect method as it loads everything in memory, use keyFilter or valueFilter to limit loaded entries. Use stream method instead
    * @return
    */
  override def collect[T](colName: CollectionName, keyFilter: RecordKey => Boolean = _ => true, valueFilter: T => Boolean = (_: T) => true)(using JsonEncoder[T], JsonDecoder[T]): IO[CollectErrors, List[T]] = {
    def collectLogic(collectionDbi: Dbi[ByteBuffer]): ZIO[Scope, CollectErrors, List[T]] = for {
      txn       <- ZIO.acquireRelease(
                     ZIO
                       .attemptBlocking(env.txnRead())
                       .mapError[CollectErrors](err => InternalError(s"Couldn't acquire read transaction on $colName", Some(err)))
                   )(txn =>
                     ZIO
                       .attemptBlocking(txn.close())
                       .ignoreLogged
                   )
      iterable  <- ZIO.acquireRelease(
                     ZIO
                       .attemptBlocking(collectionDbi.iterate(txn, KeyRange.all()))
                       .mapError[CollectErrors](err => InternalError(s"Couldn't acquire iterable on $colName", Some(err)))
                   )(cursor =>
                     ZIO
                       .attemptBlocking(cursor.close())
                       .ignoreLogged
                   )
      collected <- ZIO
                     .attempt {
                       Chunk
                         .fromIterator(EncapsulatedIterator(iterable.iterator()))
                         .filter((key, value) => keyFilter(key))
                         .flatMap((key, value) => value.fromJson[T].toOption) // TODO error are hidden !!!
                         .filter(valueFilter)
                         .toList
                     }
                     .mapError[CollectErrors](err => InternalError(s"Couldn't collect documents stored in $colName", Some(err)))
    } yield collected

    for {
      collectionDbi <- getCollectionDbi(colName)
      collected     <- ZIO.scoped(collectLogic(collectionDbi))
    } yield collected
  }

  private def extractKeyVal(keyval: KeyVal[ByteBuffer]): (String, String) = {
    val key          = keyval.key()
    val value        = keyval.`val`()
    val decodedKey   = charset.decode(key).toString
    val decodedValue = charset.decode(value).toString
    decodedKey -> decodedValue
  }

  // Encapsulation mandatory in order to make the stream work fine, without the behavior is very stange and not yet understood
  case class EncapsulatedIterator(jiterator: java.util.Iterator[KeyVal[ByteBuffer]]) extends Iterator[(String, String)] {
    override def hasNext: Boolean = jiterator.hasNext()

    override def next(): (String, String) = {
      val (key, value) = extractKeyVal(jiterator.next())
      key -> value
    }
  }

  def stream[T](dbName: String, keyFilter: String => Boolean = _ => true)(using JsonDecoder[T]): ZStream[Scope, CollectErrors, T] = {
    def streamLogic(colDbi: Dbi[ByteBuffer]): ZIO[Scope, CollectErrors, ZStream[Any, CollectErrors, T]] = for {
      txn      <- ZIO.acquireRelease(
                    ZIO
                      .attemptBlocking(env.txnRead())
                      .mapError(err => InternalError(s"Couldn't acquire read transaction on $dbName", Some(err)))
                  )(txn =>
                    ZIO
                      .attemptBlocking(txn.close())
                      .ignoreLogged
                  )
      iterable <- ZIO.acquireRelease(
                    ZIO
                      .attemptBlocking(colDbi.iterate(txn, KeyRange.all()))
                      .mapError(err => InternalError(s"Couldn't acquire iterable on $dbName", Some(err)))
                  )(cursor =>
                    ZIO
                      .attemptBlocking(cursor.close())
                      .ignoreLogged
                  )
    } yield ZStream
      .fromIterator(EncapsulatedIterator(iterable.iterator()))
      .filter((key, value) => keyFilter(key))
      .mapZIO((key, value) => ZIO.from(value.fromJson[T]).mapError(err => JsonFailure(err)))
      .mapError[JsonFailure | InternalError] {
        case err: Throwable   => InternalError(s"Couldn't stream from $dbName", Some(err))
        case err: JsonFailure => err
      }

    val result =
      for {
        db     <- getCollectionDbi(dbName)
        _      <- reentrantLock.readLock
        stream <- streamLogic(db)
      } yield stream

    ZStream.unwrap(result)
  }

}

object LMDBLive {

  private def lmdbCreateEnv(config: LMDBConfig) = {
    val syncFlag = if (!config.fileSystemSynchronized) Some(EnvFlags.MDB_NOSYNC) else None

    val flags = Array(
      EnvFlags.MDB_NOTLS,
      // MDB_NOLOCK : the caller must enforce single-writer semantics
      // MDB_NOLOCK : the caller must ensure that no readers are using old transactions while a writer is active
      EnvFlags.MDB_NOLOCK // Locks managed using ZIO ReentrantLock
    ) ++ syncFlag

    Env
      .create()
      .setMapSize(config.mapSize)
      .setMaxDbs(config.maxCollections)
      .setMaxReaders(config.maxReaders)
      .open(
        config.databasePath,
        flags*
      )
  }

  def setup(config: LMDBConfig): ZIO[Scope, Throwable, LMDBLive] = {
    for {
      environment          <- ZIO.acquireRelease(
                                ZIO.attemptBlocking(lmdbCreateEnv(config))
                              )(env => ZIO.attemptBlocking(env.close).ignoreLogged)
      openedCollectionDbis <- Ref.make[Map[String, Dbi[ByteBuffer]]](Map.empty)
      reentrantLock        <- TReentrantLock.make.commit
    } yield new LMDBLive(environment, openedCollectionDbis, reentrantLock)
  }
}
