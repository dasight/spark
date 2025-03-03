/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.streaming.state

import java.io._
import java.nio.charset.Charset

import scala.collection.mutable
import scala.language.implicitConversions

import org.apache.commons.io.FileUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.rocksdb.CompressionType
import org.scalactic.source.Position
import org.scalatest.Tag

import org.apache.spark.{SparkException, SparkUnsupportedOperationException}
import org.apache.spark.sql.catalyst.util.quietly
import org.apache.spark.sql.execution.streaming.{CreateAtomicTestManager, FileSystemBasedCheckpointFileManager}
import org.apache.spark.sql.execution.streaming.CheckpointFileManager.{CancellableFSDataOutputStream, RenameBasedFSDataOutputStream}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.internal.SQLConf.STREAMING_CHECKPOINT_FILE_MANAGER_CLASS
import org.apache.spark.sql.test.{SharedSparkSession, SQLTestUtils}
import org.apache.spark.tags.SlowSQLTest
import org.apache.spark.util.{ThreadUtils, Utils}
import org.apache.spark.util.ArrayImplicits._

class NoOverwriteFileSystemBasedCheckpointFileManager(path: Path, hadoopConf: Configuration)
  extends FileSystemBasedCheckpointFileManager(path, hadoopConf) {

  override def createAtomic(path: Path,
                            overwriteIfPossible: Boolean): CancellableFSDataOutputStream = {
    new RenameBasedFSDataOutputStream(this, path, overwriteIfPossible)
  }

  override def renameTempFile(srcPath: Path, dstPath: Path,
                              overwriteIfPossible: Boolean): Unit = {
    if (!fs.exists(dstPath)) {
      // only write if a file does not exist at this location
      super.renameTempFile(srcPath, dstPath, overwriteIfPossible)
    }
  }
}

trait RocksDBStateStoreChangelogCheckpointingTestUtil {
  val rocksdbChangelogCheckpointingConfKey: String = RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX +
    ".changelogCheckpointing.enabled"

  def isChangelogCheckpointingEnabled: Boolean =
    SQLConf.get.getConfString(rocksdbChangelogCheckpointingConfKey) == "true"

  def snapshotVersionsPresent(dir: File): Seq[Long] = {
    dir.listFiles.filter(_.getName.endsWith(".zip"))
      .map(_.getName.stripSuffix(".zip"))
      .map(_.toLong)
      .sorted
      .toImmutableArraySeq
  }

  def changelogVersionsPresent(dir: File): Seq[Long] = {
    dir.listFiles.filter(_.getName.endsWith(".changelog"))
      .map(_.getName.stripSuffix(".changelog"))
      .map(_.toLong)
      .sorted
      .toImmutableArraySeq
  }
}

trait AlsoTestWithChangelogCheckpointingEnabled
  extends SQLTestUtils with RocksDBStateStoreChangelogCheckpointingTestUtil {

  sealed trait TestMode
  case object TestWithChangelogCheckpointingEnabled extends TestMode
  case object TestWithChangelogCheckpointingDisabled extends TestMode
  case object TestWithBothChangelogCheckpointingEnabledAndDisabled extends TestMode

  override protected def test(testName: String, testTags: Tag*)(testBody: => Any)
                             (implicit pos: Position): Unit = {
    testWithChangelogCheckpointingEnabled(testName, testTags: _*)(testBody)
    testWithChangelogCheckpointingDisabled(testName, testTags: _*)(testBody)
  }

  def testWithChangelogCheckpointingEnabled(testName: String, testTags: Tag*)
                                        (testBody: => Any): Unit = {
    super.test(testName + " (with changelog checkpointing)", testTags: _*) {
      // in case tests have any code that needs to execute before every test
      super.beforeEach()
      withSQLConf(rocksdbChangelogCheckpointingConfKey -> "true",
        SQLConf.STATE_STORE_PROVIDER_CLASS.key -> classOf[RocksDBStateStoreProvider].getName) {
        testBody
      }
      // in case tests have any code that needs to execute after every test
      super.afterEach()
    }
  }

  def testWithChangelogCheckpointingDisabled(testName: String, testTags: Tag*)
                                           (testBody: => Any): Unit = {
    super.test(testName + " (without changelog checkpointing)", testTags: _*) {
      // in case tests have any code that needs to execute before every test
      super.beforeEach()
      withSQLConf(rocksdbChangelogCheckpointingConfKey -> "false",
        SQLConf.STATE_STORE_PROVIDER_CLASS.key -> classOf[RocksDBStateStoreProvider].getName) {
        testBody
      }
      // in case tests have any code that needs to execute after every test
      super.afterEach()
    }
  }

  def testWithColumnFamilies(
      testName: String,
      testMode: TestMode,
      testTags: Tag*)
      (testBody: Boolean => Any): Unit = {
    Seq(true, false).foreach { colFamiliesEnabled =>
      testMode match {
        case TestWithChangelogCheckpointingEnabled =>
          testWithChangelogCheckpointingEnabled(s"$testName - " +
            s"with colFamiliesEnabled=$colFamiliesEnabled", testTags: _*) {
            testBody(colFamiliesEnabled)
          }

        case TestWithChangelogCheckpointingDisabled =>
          testWithChangelogCheckpointingDisabled(s"$testName - " +
            s"with colFamiliesEnabled=$colFamiliesEnabled", testTags: _*) {
            testBody(colFamiliesEnabled)
          }

        case TestWithBothChangelogCheckpointingEnabledAndDisabled =>
          testWithChangelogCheckpointingEnabled(s"$testName - " +
            s"with colFamiliesEnabled=$colFamiliesEnabled", testTags: _*) {
            testBody(colFamiliesEnabled)
          }
          testWithChangelogCheckpointingDisabled(s"$testName - " +
            s"with colFamiliesEnabled=$colFamiliesEnabled", testTags: _*) {
            testBody(colFamiliesEnabled)
          }

        case _ =>
          throw new IllegalArgumentException(s"Unknown test mode: $testMode")
      }
    }
  }
}

@SlowSQLTest
class RocksDBSuite extends AlsoTestWithChangelogCheckpointingEnabled with SharedSparkSession {

  sqlConf.setConf(SQLConf.STATE_STORE_PROVIDER_CLASS, classOf[RocksDBStateStoreProvider].getName)

  testWithColumnFamilies(
    "RocksDB: check changelog and snapshot version",
    TestWithChangelogCheckpointingEnabled) { colFamiliesEnabled =>
    val remoteDir = Utils.createTempDir().toString
    val conf = dbConf.copy(minDeltasForSnapshot = 1)
    new File(remoteDir).delete() // to make sure that the directory gets created
    for (version <- 0 to 49) {
      withDB(remoteDir, version = version, conf = conf,
        useColumnFamilies = colFamiliesEnabled) { db =>
        db.put(version.toString, version.toString)
        db.commit()
        if ((version + 1) % 5 == 0) db.doMaintenance()
      }
    }

    if (isChangelogCheckpointingEnabled) {
      assert(changelogVersionsPresent(remoteDir) === (1 to 50))
      assert(snapshotVersionsPresent(remoteDir) === Range.inclusive(5, 50, 5))
    } else {
      assert(changelogVersionsPresent(remoteDir) === Seq.empty)
      assert(snapshotVersionsPresent(remoteDir) === (1 to 50))
    }
  }

  testWithColumnFamilies(s"RocksDB: load version that doesn't exist",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) {
    colFamiliesEnabled =>
    val provider = new RocksDBStateStoreProvider()
    var ex = intercept[SparkException] {
      provider.getStore(-1)
    }
    checkError(
      ex,
      errorClass = "CANNOT_LOAD_STATE_STORE.UNCATEGORIZED",
      parameters = Map.empty
    )
    ex = intercept[SparkException] {
      provider.getReadStore(-1)
    }
    checkError(
      ex,
      errorClass = "CANNOT_LOAD_STATE_STORE.UNCATEGORIZED",
      parameters = Map.empty
    )

    val remoteDir = Utils.createTempDir().toString
    new File(remoteDir).delete() // to make sure that the directory gets created
    withDB(remoteDir, useColumnFamilies = colFamiliesEnabled) { db =>
      ex = intercept[SparkException] {
        db.load(1)
      }
      checkError(
        ex,
        errorClass = "CANNOT_LOAD_STATE_STORE.CANNOT_READ_STREAMING_STATE_FILE",
        parameters = Map(
          "fileToRead" -> s"$remoteDir/1.changelog"
        )
      )
    }
  }

  testWithColumnFamilies(
    "RocksDB: purge changelog and snapshots",
    TestWithChangelogCheckpointingEnabled) { colFamiliesEnabled =>
    val remoteDir = Utils.createTempDir().toString
    new File(remoteDir).delete() // to make sure that the directory gets created
    val conf = dbConf.copy(enableChangelogCheckpointing = true,
      minVersionsToRetain = 3, minDeltasForSnapshot = 1)
    withDB(remoteDir, conf = conf, useColumnFamilies = colFamiliesEnabled) { db =>
      db.load(0)
      db.commit()
      for (version <- 1 to 2) {
        db.load(version)
        db.commit()
        db.doMaintenance()
      }
      assert(snapshotVersionsPresent(remoteDir) === Seq(2, 3))
      assert(changelogVersionsPresent(remoteDir) == Seq(1, 2, 3))

      for (version <- 3 to 4) {
        db.load(version)
        db.commit()
      }
      assert(snapshotVersionsPresent(remoteDir) === Seq(2, 3))
      assert(changelogVersionsPresent(remoteDir) == (1 to 5))
      db.doMaintenance()
      // 3 is the latest snapshot <= maxSnapshotVersionPresent - minVersionsToRetain + 1
      assert(snapshotVersionsPresent(remoteDir) === Seq(3, 5))
      assert(changelogVersionsPresent(remoteDir) == (3 to 5))

      for (version <- 5 to 7) {
        db.load(version)
        db.commit()
      }
      assert(snapshotVersionsPresent(remoteDir) === Seq(3, 5))
      assert(changelogVersionsPresent(remoteDir) == (3 to 8))
      db.doMaintenance()
      // 5 is the latest snapshot <= maxSnapshotVersionPresent - minVersionsToRetain + 1
      assert(snapshotVersionsPresent(remoteDir) === Seq(5, 8))
      assert(changelogVersionsPresent(remoteDir) == (5 to 8))
    }
  }

  testWithColumnFamilies(
    "RocksDB: minDeltasForSnapshot",
    TestWithChangelogCheckpointingEnabled) { colFamiliesEnabled =>
    val remoteDir = Utils.createTempDir().toString
    new File(remoteDir).delete() // to make sure that the directory gets created
    val conf = dbConf.copy(enableChangelogCheckpointing = true, minDeltasForSnapshot = 3)
    withDB(remoteDir, conf = conf, useColumnFamilies = colFamiliesEnabled) { db =>
      for (version <- 0 to 1) {
        db.load(version)
        db.commit()
        db.doMaintenance()
      }
      // Snapshot should not be created because minDeltasForSnapshot = 3
      assert(snapshotVersionsPresent(remoteDir) === Seq.empty)
      assert(changelogVersionsPresent(remoteDir) == Seq(1, 2))
      db.load(2)
      db.commit()
      db.doMaintenance()
      assert(snapshotVersionsPresent(remoteDir) === Seq(3))
      db.load(3)
      for (i <- 1 to 10001) {
        db.put(i.toString, i.toString)
      }
      db.commit()
      db.doMaintenance()
      // Snapshot should be created this time because the size of the change log > 1000
      assert(snapshotVersionsPresent(remoteDir) === Seq(3, 4))
      for (version <- 4 to 7) {
        db.load(version)
        db.commit()
        db.doMaintenance()
      }
      assert(snapshotVersionsPresent(remoteDir) === Seq(3, 4, 7))
      for (version <- 8 to 20) {
        db.load(version)
        db.commit()
      }
      db.doMaintenance()
      assert(snapshotVersionsPresent(remoteDir) === Seq(3, 4, 7, 19))
    }
  }

  testWithColumnFamilies("SPARK-45419: Do not reuse SST files" +
    " in different RocksDB instances",
    TestWithChangelogCheckpointingEnabled) { colFamiliesEnabled =>
    val remoteDir = Utils.createTempDir().toString
    val conf = dbConf.copy(minDeltasForSnapshot = 0, compactOnCommit = false)
    new File(remoteDir).delete() // to make sure that the directory gets created
    withDB(remoteDir, conf = conf, useColumnFamilies = colFamiliesEnabled) { db =>
      for (version <- 0 to 2) {
        db.load(version)
        db.put(version.toString, version.toString)
        db.commit()
      }
      // upload snapshot 3.zip
      db.doMaintenance()
      // Roll back to version 1 and start to process data.
      for (version <- 1 to 3) {
        db.load(version)
        db.put(version.toString, version.toString)
        db.commit()
      }
      // Upload snapshot 4.zip, should not reuse the SST files in 3.zip
      db.doMaintenance()
    }

    withDB(remoteDir, conf = conf, useColumnFamilies = colFamiliesEnabled) { db =>
      // Open the db to verify that the state in 4.zip is no corrupted.
      db.load(4)
    }
  }

  // A rocksdb instance with changelog checkpointing enabled should be able to load
  // an existing checkpoint without changelog.
  testWithColumnFamilies(
    "RocksDB: changelog checkpointing backward compatibility",
    TestWithChangelogCheckpointingEnabled) { colFamiliesEnabled =>
    val remoteDir = Utils.createTempDir().toString
    new File(remoteDir).delete() // to make sure that the directory gets created
    val disableChangelogCheckpointingConf =
      dbConf.copy(enableChangelogCheckpointing = false, minVersionsToRetain = 30)
    withDB(remoteDir, conf = disableChangelogCheckpointingConf,
      useColumnFamilies = colFamiliesEnabled) { db =>
      for (version <- 1 to 30) {
        db.load(version - 1)
        db.put(version.toString, version.toString)
        db.remove((version - 1).toString)
        db.commit()
      }
      assert(snapshotVersionsPresent(remoteDir) === (1 to 30))
    }

    // Now enable changelog checkpointing in a checkpoint created by a state store
    // that disable changelog checkpointing.
    val enableChangelogCheckpointingConf =
      dbConf.copy(enableChangelogCheckpointing = true, minVersionsToRetain = 30,
        minDeltasForSnapshot = 1)
    withDB(remoteDir, conf = enableChangelogCheckpointingConf,
      useColumnFamilies = colFamiliesEnabled) { db =>
      for (version <- 1 to 30) {
        db.load(version)
        assert(db.iterator().map(toStr).toSet === Set((version.toString, version.toString)))
      }
      for (version <- 30 to 60) {
        db.load(version - 1)
        db.put(version.toString, version.toString)
        db.remove((version - 1).toString)
        db.commit()
      }
      assert(snapshotVersionsPresent(remoteDir) === (1 to 30))
      assert(changelogVersionsPresent(remoteDir) === (30 to 60))
      for (version <- 1 to 60) {
        db.load(version, readOnly = true)
        assert(db.iterator().map(toStr).toSet === Set((version.toString, version.toString)))
      }
      // Check that snapshots and changelogs get purged correctly.
      db.doMaintenance()
      assert(snapshotVersionsPresent(remoteDir) === Seq(30, 60))
      assert(changelogVersionsPresent(remoteDir) === (30 to 60))
      // Verify the content of retained versions.
      for (version <- 30 to 60) {
        db.load(version, readOnly = true)
        assert(db.iterator().map(toStr).toSet === Set((version.toString, version.toString)))
      }
    }
  }

  // A rocksdb instance with changelog checkpointing disabled should be able to load
  // an existing checkpoint with changelog.
  testWithColumnFamilies(
    "RocksDB: changelog checkpointing forward compatibility",
    TestWithChangelogCheckpointingEnabled) { colFamiliesEnabled =>
    val remoteDir = Utils.createTempDir().toString
    new File(remoteDir).delete() // to make sure that the directory gets created
    val enableChangelogCheckpointingConf =
      dbConf.copy(enableChangelogCheckpointing = true, minVersionsToRetain = 20,
        minDeltasForSnapshot = 3)
    withDB(remoteDir, conf = enableChangelogCheckpointingConf,
      useColumnFamilies = colFamiliesEnabled) { db =>
      for (version <- 1 to 30) {
        db.load(version - 1)
        db.put(version.toString, version.toString)
        db.remove((version - 1).toString)
        db.commit()
      }
    }

    // Now disable changelog checkpointing in a checkpoint created by a state store
    // that enable changelog checkpointing.
    val disableChangelogCheckpointingConf =
    dbConf.copy(enableChangelogCheckpointing = false, minVersionsToRetain = 20,
      minDeltasForSnapshot = 1)
    withDB(remoteDir, conf = disableChangelogCheckpointingConf,
      useColumnFamilies = colFamiliesEnabled) { db =>
      for (version <- 1 to 30) {
        db.load(version)
        assert(db.iterator().map(toStr).toSet === Set((version.toString, version.toString)))
      }
      for (version <- 31 to 60) {
        db.load(version - 1)
        db.put(version.toString, version.toString)
        db.remove((version - 1).toString)
        db.commit()
      }
      assert(changelogVersionsPresent(remoteDir) === (1 to 30))
      assert(snapshotVersionsPresent(remoteDir) === (31 to 60))
      for (version <- 1 to 60) {
        db.load(version, readOnly = true)
        assert(db.iterator().map(toStr).toSet === Set((version.toString, version.toString)))
      }
      // Check that snapshots and changelogs get purged correctly.
      db.doMaintenance()
      assert(snapshotVersionsPresent(remoteDir) === (41 to 60))
      assert(changelogVersionsPresent(remoteDir) === Seq.empty)
      // Verify the content of retained versions.
      for (version <- 41 to 60) {
        db.load(version, readOnly = true)
        assert(db.iterator().map(toStr).toSet === Set((version.toString, version.toString)))
      }
    }
  }

  testWithColumnFamilies(s"RocksDB: compression conf",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    val remoteDir = Utils.createTempDir().toString
    new File(remoteDir).delete() // to make sure that the directory gets created

    val conf = RocksDBConf().copy(compression = "zstd")
    withDB(remoteDir, conf = conf, useColumnFamilies = colFamiliesEnabled) { db =>
      assert(db.columnFamilyOptions.compressionType() == CompressionType.ZSTD_COMPRESSION)
    }

    // Test the default is LZ4
    withDB(remoteDir, conf = RocksDBConf().copy(), useColumnFamilies = colFamiliesEnabled) { db =>
      assert(db.columnFamilyOptions.compressionType() == CompressionType.LZ4_COMPRESSION)
    }
  }

  testWithColumnFamilies(s"RocksDB: get, put, iterator, commit, load",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    def testOps(compactOnCommit: Boolean): Unit = {
      val remoteDir = Utils.createTempDir().toString
      new File(remoteDir).delete() // to make sure that the directory gets created

      val conf = RocksDBConf().copy(compactOnCommit = compactOnCommit)
      withDB(remoteDir, conf = conf, useColumnFamilies = colFamiliesEnabled) { db =>
        assert(db.get("a") === null)
        assert(iterator(db).isEmpty)

        db.put("a", "1")
        assert(toStr(db.get("a")) === "1")
        db.commit()
      }

      withDB(remoteDir, conf = conf, version = 0, useColumnFamilies = colFamiliesEnabled) { db =>
        // version 0 can be loaded again
        assert(toStr(db.get("a")) === null)
        assert(iterator(db).isEmpty)
      }

      withDB(remoteDir, conf = conf, version = 1, useColumnFamilies = colFamiliesEnabled) { db =>
        // version 1 data recovered correctly
        assert(toStr(db.get("a")) === "1")
        assert(db.iterator().map(toStr).toSet === Set(("a", "1")))

        // make changes but do not commit version 2
        db.put("b", "2")
        assert(toStr(db.get("b")) === "2")
        assert(db.iterator().map(toStr).toSet === Set(("a", "1"), ("b", "2")))
      }

      withDB(remoteDir, conf = conf, version = 1, useColumnFamilies = colFamiliesEnabled) { db =>
        // version 1 data not changed
        assert(toStr(db.get("a")) === "1")
        assert(db.get("b") === null)
        assert(db.iterator().map(toStr).toSet === Set(("a", "1")))

        // commit version 2
        db.put("b", "2")
        assert(toStr(db.get("b")) === "2")
        db.commit()
        assert(db.iterator().map(toStr).toSet === Set(("a", "1"), ("b", "2")))
      }

      withDB(remoteDir, conf = conf, version = 1, useColumnFamilies = colFamiliesEnabled) { db =>
        // version 1 data not changed
        assert(toStr(db.get("a")) === "1")
        assert(db.get("b") === null)
      }

      withDB(remoteDir, conf = conf, version = 2, useColumnFamilies = colFamiliesEnabled) { db =>
        // version 2 can be loaded again
        assert(toStr(db.get("b")) === "2")
        assert(db.iterator().map(toStr).toSet === Set(("a", "1"), ("b", "2")))

        db.load(1)
        assert(toStr(db.get("b")) === null)
        assert(db.iterator().map(toStr).toSet === Set(("a", "1")))
      }
    }

    for (compactOnCommit <- Seq(false, true)) {
      withClue(s"compactOnCommit = $compactOnCommit") {
        testOps(compactOnCommit)
      }
    }
  }

  testWithColumnFamilies(s"RocksDB: get, put, iterator, commit, load " +
    s"with multiple column families",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    val remoteDir = Utils.createTempDir().toString
    new File(remoteDir).delete() // to make sure that the directory gets created
    val colFamily1: String = "abc"
    val colFamily2: String = "xyz"

    val conf = RocksDBConf().copy()
    withDB(remoteDir, conf = conf, useColumnFamilies = true) { db =>
      val ex = intercept[Exception] {
        db.createColFamilyIfAbsent("default")
      }
      ex.getCause.isInstanceOf[UnsupportedOperationException]
    }

    withDB(remoteDir, conf = conf, useColumnFamilies = true) { db =>
      db.createColFamilyIfAbsent(colFamily1)
      db.createColFamilyIfAbsent(colFamily2)

      assert(db.get("a", colFamily1) === null)
      assert(iterator(db, colFamily1).isEmpty)

      db.put("a", "1", colFamily1)
      assert(toStr(db.get("a", colFamily1)) === "1")

      assert(db.get("a", colFamily2) === null)
      assert(iterator(db, colFamily2).isEmpty)

      db.put("a", "1", colFamily2)
      assert(toStr(db.get("a", colFamily2)) === "1")

      db.commit()
    }

    withDB(remoteDir, conf = conf, version = 0, useColumnFamilies = true) { db =>
      val ex = intercept[Exception] {
        // version 0 can be loaded again
        assert(toStr(db.get("a", colFamily1)) === null)
        assert(iterator(db, colFamily1).isEmpty)

        // version 0 can be loaded again
        assert(toStr(db.get("a", colFamily2)) === null)
        assert(iterator(db, colFamily2).isEmpty)
      }
      assert(ex.isInstanceOf[RuntimeException])
      assert(ex.getMessage.contains("does not exist"))
    }

    withDB(remoteDir, conf = conf, version = 1, useColumnFamilies = true) { db =>
      // version 1 data recovered correctly
      assert(toStr(db.get("a", colFamily1)) === "1")
      assert(db.iterator(colFamily1).map(toStr).toSet === Set(("a", "1")))

      // make changes but do not commit version 2
      db.put("b", "2", colFamily1)
      assert(toStr(db.get("b", colFamily1)) === "2")
      assert(db.iterator(colFamily1).map(toStr).toSet === Set(("a", "1"), ("b", "2")))

      // version 1 data recovered correctly
      assert(toStr(db.get("a", colFamily2)) === "1")
      assert(db.iterator(colFamily2).map(toStr).toSet === Set(("a", "1")))

      // make changes but do not commit version 2
      db.put("b", "2", colFamily2)
      assert(toStr(db.get("b", colFamily2)) === "2")
      assert(db.iterator(colFamily2).map(toStr).toSet === Set(("a", "1"), ("b", "2")))
    }

    withDB(remoteDir, conf = conf, version = 1, useColumnFamilies = true) { db =>
      // version 1 data not changed
      assert(toStr(db.get("a", colFamily1)) === "1")
      assert(db.get("b", colFamily1) === null)
      assert(db.iterator(colFamily1).map(toStr).toSet === Set(("a", "1")))

      assert(toStr(db.get("a", colFamily2)) === "1")
      assert(db.get("b", colFamily2) === null)
      assert(db.iterator(colFamily2).map(toStr).toSet === Set(("a", "1")))

      // commit version 2
      db.put("b", "2", colFamily1)
      assert(toStr(db.get("b", colFamily1)) === "2")
      assert(db.iterator(colFamily1).map(toStr).toSet === Set(("a", "1"), ("b", "2")))

      db.put("b", "2", colFamily2)
      assert(toStr(db.get("b", colFamily2)) === "2")
      db.commit()
      assert(db.iterator(colFamily2).map(toStr).toSet === Set(("a", "1"), ("b", "2")))
    }

    withDB(remoteDir, conf = conf, version = 1, useColumnFamilies = true) { db =>
      // version 1 data not changed
      assert(toStr(db.get("a", colFamily1)) === "1")
      assert(db.get("b", colFamily1) === null)

      assert(toStr(db.get("a", colFamily2)) === "1")
      assert(db.get("b", colFamily2) === null)
    }

    withDB(remoteDir, conf = conf, version = 2, useColumnFamilies = true) { db =>
      // version 2 can be loaded again
      assert(toStr(db.get("b", colFamily1)) === "2")
      assert(db.iterator(colFamily1).map(toStr).toSet === Set(("a", "1"), ("b", "2")))

      assert(toStr(db.get("b", colFamily2)) === "2")
      assert(db.iterator(colFamily2).map(toStr).toSet === Set(("a", "1"), ("b", "2")))

      db.load(1)
      assert(toStr(db.get("b", colFamily1)) === null)
      assert(db.iterator(colFamily1).map(toStr).toSet === Set(("a", "1")))

      db.load(1)
      assert(toStr(db.get("b", colFamily2)) === null)
      assert(db.iterator(colFamily2).map(toStr).toSet === Set(("a", "1")))
    }
  }

  testWithColumnFamilies(s"RocksDB: handle commit failures and aborts",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    val hadoopConf = new Configuration()
    hadoopConf.set(
      SQLConf.STREAMING_CHECKPOINT_FILE_MANAGER_CLASS.parent.key,
      classOf[CreateAtomicTestManager].getName)
    val remoteDir = Utils.createTempDir().getAbsolutePath
    withDB(remoteDir, hadoopConf = hadoopConf, useColumnFamilies = colFamiliesEnabled) { db =>
      // Disable failure of output stream and generate versions
      CreateAtomicTestManager.shouldFailInCreateAtomic = false
      for (version <- 1 to 10) {
        db.load(version - 1)
        db.put(version.toString, version.toString) // update "1" -> "1", "2" -> "2", ...
        db.commit()
      }
      val version10Data = (1L to 10).map(_.toString).map(x => x -> x).toSet

      // Fail commit for next version and verify that reloading resets the files
      CreateAtomicTestManager.shouldFailInCreateAtomic = true
      db.load(10)
      db.put("11", "11")
      intercept[IOException] {
        quietly {
          db.commit()
        }
      }
      assert(db.load(10, readOnly = true).iterator().map(toStr).toSet === version10Data)
      CreateAtomicTestManager.shouldFailInCreateAtomic = false

      // Abort commit for next version and verify that reloading resets the files
      db.load(10)
      db.put("11", "11")
      db.rollback()
      assert(db.load(10, readOnly = true).iterator().map(toStr).toSet === version10Data)
    }
  }

  testWithChangelogCheckpointingEnabled("RocksDB: Unsupported Operations" +
    " with Changelog Checkpointing") {
    val dfsRootDir = new File(Utils.createTempDir().getAbsolutePath + "/state/1/1")
    val fileManager = new RocksDBFileManager(
      dfsRootDir.getAbsolutePath, Utils.createTempDir(), new Configuration)
    val changelogWriter = fileManager.getChangeLogWriter(1)

    val ex1 = intercept[SparkUnsupportedOperationException] {
      changelogWriter.put("a", "1", "testColFamily")
    }

    checkError(
      ex1,
      errorClass = "STATE_STORE_UNSUPPORTED_OPERATION",
      parameters = Map(
        "operationType" -> "Put",
        "entity" -> "changelog writer v1"
      ),
      matchPVals = true
    )
    val ex2 = intercept[SparkUnsupportedOperationException] {
      changelogWriter.delete("a", "testColFamily")
    }

    checkError(
      ex2,
      errorClass = "STATE_STORE_UNSUPPORTED_OPERATION",
      parameters = Map(
        "operationType" -> "Delete",
        "entity" -> "changelog writer v1"
      ),
      matchPVals = true
    )
  }

  testWithChangelogCheckpointingEnabled("RocksDBFileManager: read and write changelog") {
    val dfsRootDir = new File(Utils.createTempDir().getAbsolutePath + "/state/1/1")
    val fileManager = new RocksDBFileManager(
      dfsRootDir.getAbsolutePath, Utils.createTempDir(), new Configuration)
    val changelogWriter = fileManager.getChangeLogWriter(1)

    (1 to 5).foreach(i => changelogWriter.put(i.toString, i.toString))
    (2 to 4).foreach(j => changelogWriter.delete(j.toString))

    changelogWriter.commit()
    val changelogReader = fileManager.getChangelogReader(1)
    val entries = changelogReader.toSeq
    val expectedEntries = (1 to 5).map { i =>
      (RecordType.PUT_RECORD, i.toString.getBytes,
        i.toString.getBytes, StateStore.DEFAULT_COL_FAMILY_NAME)
    } ++ (2 to 4).map { j =>
      (RecordType.DELETE_RECORD, j.toString.getBytes,
        null, StateStore.DEFAULT_COL_FAMILY_NAME)
    }

    assert(entries.size == expectedEntries.size)
    entries.zip(expectedEntries).map{
      case (e1, e2) => assert(e1._1 === e2._1 && e1._2 === e2._2 &&
        e1._3 === e2._3 && e1._4 === e2._4)
    }
  }

  testWithChangelogCheckpointingEnabled(
    "RocksDBFileManager: read and write v2 changelog with default col family") {
    val dfsRootDir = new File(Utils.createTempDir().getAbsolutePath + "/state/1/1")
    val fileManager = new RocksDBFileManager(
      dfsRootDir.getAbsolutePath, Utils.createTempDir(), new Configuration)
    val changelogWriter = fileManager.getChangeLogWriter(1, true)
    (1 to 5).foreach { i =>
      changelogWriter.put(i.toString, i.toString, StateStore.DEFAULT_COL_FAMILY_NAME)
    }
    (1 to 5).foreach { i =>
      changelogWriter.merge(i.toString, i.toString, StateStore.DEFAULT_COL_FAMILY_NAME)
    }

    (2 to 4).foreach { j =>
      changelogWriter.delete(j.toString, StateStore.DEFAULT_COL_FAMILY_NAME)
    }

    changelogWriter.commit()
    val changelogReader = fileManager.getChangelogReader(1, true)
    val entries = changelogReader.toSeq
    val expectedEntries = (1 to 5).map { i =>
      (RecordType.PUT_RECORD, i.toString.getBytes,
        i.toString.getBytes, StateStore.DEFAULT_COL_FAMILY_NAME)
    } ++ (1 to 5).map { i =>
      (RecordType.MERGE_RECORD, i.toString.getBytes,
        i.toString.getBytes, StateStore.DEFAULT_COL_FAMILY_NAME)
    } ++ (2 to 4).map { j =>
      (RecordType.DELETE_RECORD, j.toString.getBytes,
        null, StateStore.DEFAULT_COL_FAMILY_NAME)
    }

    assert(entries.size == expectedEntries.size)
    entries.zip(expectedEntries).map{
      case (e1, e2) => assert(e1._1 === e2._1 && e1._2 === e2._2 &&
        e1._3 === e2._3 && e1._4 === e2._4)
    }
  }

  testWithChangelogCheckpointingEnabled(
    "RocksDBFileManager: read and write v2 changelog with multiple col families") {
    val dfsRootDir = new File(Utils.createTempDir().getAbsolutePath + "/state/1/1")
    val testColFamily1: String = "testColFamily1"
    val testColFamily2: String = "testColFamily2"
    val fileManager = new RocksDBFileManager(
      dfsRootDir.getAbsolutePath, Utils.createTempDir(), new Configuration)
    val changelogWriter = fileManager.getChangeLogWriter(1, true)
    (1 to 5).foreach(i => changelogWriter.put(i.toString, i.toString, testColFamily1))
    (1 to 5).foreach(i => changelogWriter.merge(i.toString, i.toString, testColFamily1))
    (2 to 4).foreach(j => changelogWriter.delete(j.toString, testColFamily1))

    (1 to 5).foreach(i => changelogWriter.put(i.toString, i.toString, testColFamily2))
    (1 to 5).foreach(i => changelogWriter.merge(i.toString, i.toString, testColFamily2))
    (2 to 4).foreach(j => changelogWriter.delete(j.toString, testColFamily2))

    changelogWriter.commit()
    val changelogReader = fileManager.getChangelogReader(1, true)
    val entries = changelogReader.toSeq
    val expectedEntriesForColFamily1 = (1 to 5).map { i =>
      (RecordType.PUT_RECORD, i.toString.getBytes,
        i.toString.getBytes, testColFamily1)
    } ++ (1 to 5).map { i =>
      (RecordType.MERGE_RECORD, i.toString.getBytes,
        i.toString.getBytes, testColFamily1)
    } ++ (2 to 4).map { j =>
      (RecordType.DELETE_RECORD, j.toString.getBytes,
        null, testColFamily1)
    }

    val expectedEntriesForColFamily2 = (1 to 5).map { i =>
      (RecordType.PUT_RECORD, i.toString.getBytes,
        i.toString.getBytes, testColFamily2)
    } ++ (1 to 5).map { i =>
      (RecordType.MERGE_RECORD, i.toString.getBytes,
        i.toString.getBytes, testColFamily2)
    } ++ (2 to 4).map { j =>
      (RecordType.DELETE_RECORD, j.toString.getBytes,
        null, testColFamily2)
    }

    val expectedEntries = expectedEntriesForColFamily1 ++ expectedEntriesForColFamily2
    assert(entries.size == expectedEntries.size)
    entries.zip(expectedEntries).map{
      case (e1, e2) => assert(e1._1 === e2._1 && e1._2 === e2._2 &&
        e1._3 === e2._3 && e1._4 === e2._4)
    }
  }

  testWithColumnFamilies("RocksDBFileManager: create init dfs directory with " +
    s"unknown number of keys",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    val dfsRootDir = new File(Utils.createTempDir().getAbsolutePath + "/state/1/1")
    try {
      val verificationDir = Utils.createTempDir().getAbsolutePath
      val fileManager = new RocksDBFileManager(
        dfsRootDir.getAbsolutePath, Utils.createTempDir(), new Configuration)
      // Save a version of empty checkpoint files
      val cpFiles = Seq()
      generateFiles(verificationDir, cpFiles)
      assert(!dfsRootDir.exists())
      saveCheckpointFiles(fileManager, cpFiles, version = 1, numKeys = -1)
      // The dfs root dir is created even with unknown number of keys
      assert(dfsRootDir.exists())
      loadAndVerifyCheckpointFiles(fileManager, verificationDir, version = 1, Nil, -1)
    } finally {
      Utils.deleteRecursively(dfsRootDir)
    }
  }

  test("ensure merge operation is not supported if column families is not enabled") {
    withTempDir { dir =>
      val remoteDir = Utils.createTempDir().toString
      val conf = dbConf.copy(minDeltasForSnapshot = 5, compactOnCommit = false)
      new File(remoteDir).delete() // to make sure that the directory gets created
      withDB(remoteDir, conf = conf, useColumnFamilies = false) { db =>
        db.load(0)
        db.put("a", "1")
        intercept[RuntimeException](
          db.merge("a", "2")
        )
      }
    }
  }

  test("RocksDB: ensure merge operation correctness") {
    withTempDir { dir =>
      val remoteDir = Utils.createTempDir().toString
      // minDeltasForSnapshot being 5 ensures that only changelog files are created
      // for the 3 commits below
      val conf = dbConf.copy(minDeltasForSnapshot = 5, compactOnCommit = false)
      new File(remoteDir).delete() // to make sure that the directory gets created
      withDB(remoteDir, conf = conf, useColumnFamilies = true) { db =>
        db.load(0)
        db.createColFamilyIfAbsent("cf1")
        db.createColFamilyIfAbsent("cf2")
        db.put("a", "1", "cf1")
        db.merge("a", "2", "cf1")
        db.put("a", "3", "cf2")
        db.commit()

        db.load(1)
        db.put("a", "2")
        db.merge("a", "3", "cf1")
        db.merge("a", "4", "cf2")
        db.commit()

        db.load(2)
        db.remove("a", "cf1")
        db.merge("a", "5")
        db.merge("a", "6", "cf2")
        db.commit()

        db.load(1)
        assert(new String(db.get("a", "cf1")) === "1,2")
        assert(new String(db.get("a", "cf2")) === "3")
        assert(db.get("a") === null)
        assert(db.iterator("cf1").map(toStr).toSet === Set(("a", "1,2")))
        assert(db.iterator("cf2").map(toStr).toSet === Set(("a", "3")))
        assert(db.iterator().isEmpty)

        db.load(2)
        assert(new String(db.get("a", "cf1")) === "1,2,3")
        assert(new String(db.get("a", "cf2")) === "3,4")
        assert(new String(db.get("a")) === "2")
        assert(db.iterator("cf1").map(toStr).toSet === Set(("a", "1,2,3")))
        assert(db.iterator("cf2").map(toStr).toSet === Set(("a", "3,4")))
        assert(db.iterator().map(toStr).toSet === Set(("a", "2")))

        db.load(3)
        assert(db.get("a", "cf1") === null)
        assert(new String(db.get("a", "cf2")) === "3,4,6")
        assert(new String(db.get("a")) === "2,5")
        assert(db.iterator("cf1").isEmpty)
        assert(db.iterator("cf2").map(toStr).toSet === Set(("a", "3,4,6")))
        assert(db.iterator().map(toStr).toSet === Set(("a", "2,5")))
      }
    }
  }

  testWithColumnFamilies("RocksDBFileManager: delete orphan files",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    withTempDir { dir =>
      val dfsRootDir = dir.getAbsolutePath
      // Use 2 file managers here to emulate concurrent execution
      // that checkpoint the same version of state
      val fileManager = new RocksDBFileManager(
        dfsRootDir, Utils.createTempDir(), new Configuration)
      val fileManager_ = new RocksDBFileManager(
        dfsRootDir, Utils.createTempDir(), new Configuration)
      val sstDir = s"$dfsRootDir/SSTs"
      def numRemoteSSTFiles: Int = listFiles(sstDir).length
      val logDir = s"$dfsRootDir/logs"
      def numRemoteLogFiles: Int = listFiles(logDir).length

      // Save a version of checkpoint files
      val cpFiles1 = Seq(
        "001.sst" -> 10,
        "002.sst" -> 20,
        "other-file1" -> 100,
        "other-file2" -> 200,
        "archive/00001.log" -> 1000,
        "archive/00002.log" -> 2000
      )
      saveCheckpointFiles(fileManager, cpFiles1, version = 1, numKeys = 101)
      assert(fileManager.getLatestVersion() === 1)
      assert(numRemoteSSTFiles == 2) // 2 sst files copied
      assert(numRemoteLogFiles == 2)

      // Overwrite version 1, previous sst and log files will become orphan
      val cpFiles1_ = Seq(
        "001.sst" -> 10,
        "002.sst" -> 20,
        "other-file1" -> 100,
        "other-file2" -> 200,
        "archive/00002.log" -> 1000,
        "archive/00003.log" -> 2000
      )
      saveCheckpointFiles(fileManager_, cpFiles1_, version = 1, numKeys = 101)
      assert(fileManager_.getLatestVersion() === 1)
      assert(numRemoteSSTFiles == 4)
      assert(numRemoteLogFiles == 4)

      // For orphan files cleanup test, add a sleep between 2 checkpoints.
      // We use file modification timestamp to find orphan files older than
      // any tracked files. Some file systems has timestamps in second precision.
      // Sleeping for 1.5s makes sure files from different versions has different timestamps.
      Thread.sleep(1500)
      // Save a version of checkpoint files
      val cpFiles2 = Seq(
        "003.sst" -> 10,
        "004.sst" -> 20,
        "other-file1" -> 100,
        "other-file2" -> 200,
        "archive/00004.log" -> 1000,
        "archive/00005.log" -> 2000
      )
      saveCheckpointFiles(fileManager_, cpFiles2, version = 2, numKeys = 121)
      fileManager_.deleteOldVersions(1)
      assert(numRemoteSSTFiles <= 4) // delete files recorded in 1.zip
      assert(numRemoteLogFiles <= 5) // delete files recorded in 1.zip and orphan 00001.log

      Thread.sleep(1500)
      // Save a version of checkpoint files
      val cpFiles3 = Seq(
        "005.sst" -> 10,
        "other-file1" -> 100,
        "other-file2" -> 200,
        "archive/00006.log" -> 1000,
        "archive/00007.log" -> 2000
      )
      saveCheckpointFiles(fileManager_, cpFiles3, version = 3, numKeys = 131)
      assert(fileManager_.getLatestVersion() === 3)
      fileManager_.deleteOldVersions(1)
      assert(numRemoteSSTFiles == 1)
      assert(numRemoteLogFiles == 2)
    }
  }

  testWithColumnFamilies("RocksDBFileManager: don't delete orphan files " +
    s"when there is only 1 version",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    withTempDir { dir =>
      val dfsRootDir = dir.getAbsolutePath
      val fileManager = new RocksDBFileManager(
        dfsRootDir, Utils.createTempDir(), new Configuration)
      (new File(dfsRootDir, "SSTs")).mkdir()
      (new File(dfsRootDir, "logs")).mkdir()

      val sstDir = s"$dfsRootDir/SSTs"
      def numRemoteSSTFiles: Int = listFiles(sstDir).length

      val logDir = s"$dfsRootDir/logs"
      def numRemoteLogFiles: Int = listFiles(logDir).length

      new File(sstDir, "orphan.sst").createNewFile()
      new File(logDir, "orphan.log").createNewFile()

      Thread.sleep(1500)
      // Save a version of checkpoint files
      val cpFiles1 = Seq(
        "001.sst" -> 10,
        "002.sst" -> 20,
        "other-file1" -> 100,
        "other-file2" -> 200,
        "archive/00001.log" -> 1000,
        "archive/00002.log" -> 2000
      )
      saveCheckpointFiles(fileManager, cpFiles1, version = 1, numKeys = 101)
      fileManager.deleteOldVersions(1)
      // Should not delete orphan files even when they are older than all existing files
      // when there is only 1 version.
      assert(numRemoteSSTFiles == 3)
      assert(numRemoteLogFiles == 3)

      Thread.sleep(1500)
      // Save a version of checkpoint files
      val cpFiles2 = Seq(
        "003.sst" -> 10,
        "004.sst" -> 20,
        "other-file1" -> 100,
        "other-file2" -> 200,
        "archive/00003.log" -> 1000,
        "archive/00004.log" -> 2000
      )
      saveCheckpointFiles(fileManager, cpFiles2, version = 2, numKeys = 101)
      assert(numRemoteSSTFiles == 5)
      assert(numRemoteLogFiles == 5)
      fileManager.deleteOldVersions(1)
      // Orphan files should be deleted now.
      assert(numRemoteSSTFiles == 2)
      assert(numRemoteLogFiles == 2)
    }
  }

  testWithColumnFamilies("RocksDBFileManager: upload only new immutable files",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    withTempDir { dir =>
      val dfsRootDir = dir.getAbsolutePath
      val verificationDir = Utils.createTempDir().getAbsolutePath // local dir to load checkpoints
      val fileManager = new RocksDBFileManager(
        dfsRootDir, Utils.createTempDir(), new Configuration)
      val sstDir = s"$dfsRootDir/SSTs"
      def numRemoteSSTFiles: Int = listFiles(sstDir).length
      val logDir = s"$dfsRootDir/logs"
      def numRemoteLogFiles: Int = listFiles(logDir).length

      // Verify behavior before any saved checkpoints
      assert(fileManager.getLatestVersion() === 0)

      // Try to load incorrect versions
      intercept[FileNotFoundException] {
        fileManager.loadCheckpointFromDfs(1, Utils.createTempDir())
      }

      // Save a version of checkpoint files
      val cpFiles1 = Seq(
        "sst-file1.sst" -> 10,
        "sst-file2.sst" -> 20,
        "other-file1" -> 100,
        "other-file2" -> 200,
        "archive/00001.log" -> 1000,
        "archive/00002.log" -> 2000
      )
      saveCheckpointFiles(fileManager, cpFiles1, version = 1, numKeys = 101)
      assert(fileManager.getLatestVersion() === 1)
      assert(numRemoteSSTFiles == 2) // 2 sst files copied
      assert(numRemoteLogFiles == 2) // 2 log files copied

      // Load back the checkpoint files into another local dir with existing files and verify
      generateFiles(verificationDir, Seq(
        "sst-file1.sst" -> 11, // files with same name but different sizes, should get overwritten
        "other-file1" -> 101,
        "archive/00001.log" -> 1001,
        "random-sst-file.sst" -> 100, // unnecessary files, should get deleted
        "random-other-file" -> 9,
        "00005.log" -> 101,
        "archive/00007.log" -> 101
      ))
      loadAndVerifyCheckpointFiles(fileManager, verificationDir, version = 1, cpFiles1, 101)

      // Save SAME version again with different checkpoint files and load back again to verify
      // whether files were overwritten.
      val cpFiles1_ = Seq(
        "sst-file1.sst" -> 10, // same SST file as before, this should get reused
        "sst-file2.sst" -> 25, // new SST file with same name as before, but different length
        "sst-file3.sst" -> 30, // new SST file
        "other-file1" -> 100, // same non-SST file as before, should not get copied
        "other-file2" -> 210, // new non-SST file with same name as before, but different length
        "other-file3" -> 300, // new non-SST file
        "archive/00001.log" -> 1000, // same log file as before, this should get reused
        "archive/00002.log" -> 2500, // new log file with same name as before, but different length
        "archive/00003.log" -> 3000 // new log file
      )
      saveCheckpointFiles(fileManager, cpFiles1_, version = 1, numKeys = 1001)
      assert(numRemoteSSTFiles === 4, "shouldn't copy same files again") // 2 old + 2 new SST files
      assert(numRemoteLogFiles === 4, "shouldn't copy same files again") // 2 old + 2 new log files
      loadAndVerifyCheckpointFiles(fileManager, verificationDir, version = 1, cpFiles1_, 1001)

      // Save another version and verify
      val cpFiles2 = Seq(
        "sst-file4.sst" -> 40,
        "other-file4" -> 400,
        "archive/00004.log" -> 4000
      )
      saveCheckpointFiles(fileManager, cpFiles2, version = 2, numKeys = 1501)
      assert(numRemoteSSTFiles === 5) // 1 new file over earlier 4 files
      assert(numRemoteLogFiles === 5) // 1 new file over earlier 4 files
      loadAndVerifyCheckpointFiles(fileManager, verificationDir, version = 2, cpFiles2, 1501)

      // Loading an older version should work
      loadAndVerifyCheckpointFiles(fileManager, verificationDir, version = 1, cpFiles1_, 1001)

      // Loading incorrect version should fail
      intercept[FileNotFoundException] {
        loadAndVerifyCheckpointFiles(fileManager, verificationDir, version = 3, Nil, 1001)
      }

      // Loading 0 should delete all files
      require(verificationDir.list().length > 0)
      loadAndVerifyCheckpointFiles(fileManager, verificationDir, version = 0, Nil, 0)
    }
  }

  testWithColumnFamilies("RocksDBFileManager: error writing [version].zip " +
    s"cancels the output stream",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    quietly {
      val hadoopConf = new Configuration()
      hadoopConf.set(
        SQLConf.STREAMING_CHECKPOINT_FILE_MANAGER_CLASS.parent.key,
        classOf[CreateAtomicTestManager].getName)
      val dfsRootDir = Utils.createTempDir().getAbsolutePath
      val fileManager = new RocksDBFileManager(dfsRootDir, Utils.createTempDir(), hadoopConf)
      val cpFiles = Seq("sst-file1.sst" -> 10, "sst-file2.sst" -> 20, "other-file1" -> 100)
      CreateAtomicTestManager.shouldFailInCreateAtomic = true
      intercept[IOException] {
        saveCheckpointFiles(fileManager, cpFiles, version = 1, numKeys = 101)
      }
      assert(CreateAtomicTestManager.cancelCalledInCreateAtomic)
    }
  }

  testWithColumnFamilies("disallow concurrent updates to the same RocksDB instance",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    quietly {
      withDB(
        Utils.createTempDir().toString,
        conf = dbConf.copy(lockAcquireTimeoutMs = 20),
        useColumnFamilies = colFamiliesEnabled) { db =>
        // DB has been loaded so current thread has already
        // acquired the lock on the RocksDB instance

        db.load(0) // Current thread should be able to load again

        // Another thread should not be able to load while current thread is using it
        var ex = intercept[SparkException] {
          ThreadUtils.runInNewThread("concurrent-test-thread-1") {
            db.load(0)
          }
        }
        checkError(
          ex,
          errorClass = "CANNOT_LOAD_STATE_STORE.UNRELEASED_THREAD_ERROR",
          parameters = Map(
            "loggingId" -> "\\[Thread-\\d+\\]",
            "newAcquiredThreadInfo" -> "\\[ThreadId: Some\\(\\d+\\)\\]",
            "acquiredThreadInfo" -> "\\[ThreadId: Some\\(\\d+\\)\\]",
            "timeWaitedMs" -> "\\d+",
            "stackTraceOutput" -> "(?s).*"
          ),
          matchPVals = true
        )

        // Commit should release the instance allowing other threads to load new version
        db.commit()
        ThreadUtils.runInNewThread("concurrent-test-thread-2") {
          db.load(1)
          db.commit()
        }

        // Another thread should not be able to load while current thread is using it
        db.load(2)
        ex = intercept[SparkException] {
          ThreadUtils.runInNewThread("concurrent-test-thread-2") {
            db.load(2)
          }
        }
        checkError(
          ex,
          errorClass = "CANNOT_LOAD_STATE_STORE.UNRELEASED_THREAD_ERROR",
          parameters = Map(
            "loggingId" -> "\\[Thread-\\d+\\]",
            "newAcquiredThreadInfo" -> "\\[ThreadId: Some\\(\\d+\\)\\]",
            "acquiredThreadInfo" -> "\\[ThreadId: Some\\(\\d+\\)\\]",
            "timeWaitedMs" -> "\\d+",
            "stackTraceOutput" -> "(?s).*"
          ),
          matchPVals = true
        )

        // Rollback should release the instance allowing other threads to load new version
        db.rollback()
        ThreadUtils.runInNewThread("concurrent-test-thread-3") {
          db.load(1)
          db.commit()
        }
      }
    }
  }

  testWithColumnFamilies("ensure concurrent access lock is released after Spark task completes",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    RocksDBSuite.withSingletonDB {
      // Load a RocksDB instance, that is, get a lock inside a task and then fail
      quietly {
        intercept[Exception] {
          sparkContext.makeRDD[Int](1 to 1, 1).map { i =>
            RocksDBSuite.singleton.load(0)
            throw new Exception("fail this task to test lock release")
          }.count()
        }
      }

      // Test whether you can load again, that is, will it successfully lock again
      RocksDBSuite.singleton.load(0)
    }
  }

  testWithColumnFamilies("checkpoint metadata serde roundtrip",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    // expect read metadata error when metadata uses unsupported version
    withTempDir { dir =>
      val file2 = new File(dir, "json")
      val json2 = """{"sstFiles":[],"numKeys":0}"""
      FileUtils.write(file2, s"v2\n$json2", Charset.defaultCharset)
      val e = intercept[SparkException] {
        RocksDBCheckpointMetadata.readFromFile(file2)
      }
      checkError(
        e,
        errorClass = "CANNOT_LOAD_STATE_STORE.CANNOT_READ_CHECKPOINT",
        parameters = Map(
          "expectedVersion" -> "v2",
          "actualVersion" -> "v1"
        )
      )
    }

    def checkJsonRoundtrip(metadata: RocksDBCheckpointMetadata, json: String): Unit = {
      assert(metadata.json == json)
      withTempDir { dir =>
        val file = new File(dir, "json")
        FileUtils.write(file, s"v1\n$json", Charset.defaultCharset)
        assert(metadata == RocksDBCheckpointMetadata.readFromFile(file))
      }
    }
    val sstFiles = Seq(RocksDBSstFile("00001.sst", "00001-uuid.sst", 12345678901234L))
    val logFiles = Seq(RocksDBLogFile("00001.log", "00001-uuid.log", 12345678901234L))

    // scalastyle:off line.size.limit
    // should always include sstFiles and numKeys
    checkJsonRoundtrip(
      RocksDBCheckpointMetadata(Seq.empty, 0L),
      """{"sstFiles":[],"numKeys":0}"""
    )
    // shouldn't include the "logFiles" field in json when it's empty
    checkJsonRoundtrip(
      RocksDBCheckpointMetadata(sstFiles, 12345678901234L),
      """{"sstFiles":[{"localFileName":"00001.sst","dfsSstFileName":"00001-uuid.sst","sizeBytes":12345678901234}],"numKeys":12345678901234}"""
    )
    checkJsonRoundtrip(
      RocksDBCheckpointMetadata(sstFiles, logFiles, 12345678901234L),
      """{"sstFiles":[{"localFileName":"00001.sst","dfsSstFileName":"00001-uuid.sst","sizeBytes":12345678901234}],"logFiles":[{"localFileName":"00001.log","dfsLogFileName":"00001-uuid.log","sizeBytes":12345678901234}],"numKeys":12345678901234}""")
    // scalastyle:on line.size.limit
  }

  testWithColumnFamilies("SPARK-36236: reset RocksDB metrics whenever a new version is loaded",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    def verifyMetrics(putCount: Long, getCount: Long, iterCountPositive: Boolean = false,
                      metrics: RocksDBMetrics): Unit = {
      assert(metrics.nativeOpsHistograms("put").count === putCount, "invalid put count")
      assert(metrics.nativeOpsHistograms("get").count === getCount, "invalid get count")
      if (iterCountPositive) {
        assert(metrics.nativeOpsMetrics("totalBytesReadThroughIterator") > 0)
      } else {
        assert(metrics.nativeOpsMetrics("totalBytesReadThroughIterator") === 0)
      }

      // most of the time get reads from WriteBatch which is not counted in this metric
      assert(metrics.nativeOpsMetrics("totalBytesRead") >= 0)
      assert(metrics.nativeOpsMetrics("totalBytesWritten") >= putCount * 1)

      assert(metrics.nativeOpsHistograms("compaction") != null)
      assert(metrics.nativeOpsMetrics("readBlockCacheMissCount") >= 0)
      assert(metrics.nativeOpsMetrics("readBlockCacheHitCount") >= 0)

      assert(metrics.nativeOpsMetrics("writerStallDuration") >= 0)
      assert(metrics.nativeOpsMetrics("totalBytesReadByCompaction") >= 0)
      assert(metrics.nativeOpsMetrics("totalBytesWrittenByCompaction") >=0)

      assert(metrics.nativeOpsMetrics("totalBytesWrittenByFlush") >= 0)
    }

    withTempDir { dir =>
      val remoteDir = dir.getCanonicalPath
      withDB(remoteDir, useColumnFamilies = colFamiliesEnabled) { db =>
        db.load(0)
        db.put("a", "1") // put also triggers a db get
        db.get("a") // this is found in-memory writebatch - no get triggered in db
        db.get("b") // key doesn't exists - triggers db get
        db.commit()
        verifyMetrics(putCount = 1, getCount = 3, metrics = db.metricsOpt.get)

        db.load(1)
        db.put("b", "2") // put also triggers a db get
        db.get("a") // not found in-memory writebatch, so triggers a db get
        db.get("c") // key doesn't exists - triggers db get
        assert(iterator(db).toSet === Set(("a", "1"), ("b", "2")))
        db.commit()
        verifyMetrics(putCount = 1, getCount = 3, iterCountPositive = true, db.metricsOpt.get)
      }
    }

    // disable resetting stats
    withTempDir { dir =>
      val remoteDir = dir.getCanonicalPath
      withDB(remoteDir,
        conf = dbConf.copy(resetStatsOnLoad = false),
        useColumnFamilies = colFamiliesEnabled) { db =>
        db.load(0)
        db.put("a", "1") // put also triggers a db get
        db.commit()
        // put and get counts are cumulative
        verifyMetrics(putCount = 1, getCount = 1, metrics = db.metricsOpt.get)

        db.load(1)
        db.put("b", "2") // put also triggers a db get
        db.get("a")
        db.commit()
        // put and get counts are cumulative: existing get=1, put=1: new get=2, put=1
        verifyMetrics(putCount = 2, getCount = 3, metrics = db.metricsOpt.get)
      }
    }

    // force compaction and check the compaction metrics
    withTempDir { dir =>
      val remoteDir = dir.getCanonicalPath
      withDB(remoteDir,
        conf = RocksDBConf().copy(compactOnCommit = true),
        useColumnFamilies = colFamiliesEnabled) { db =>
        db.load(0)
        db.put("a", "5")
        db.put("b", "5")
        db.commit()

        db.load(1)
        db.put("a", "10")
        db.put("b", "25")
        db.commit()

        val metrics = db.metricsOpt.get
        assert(metrics.nativeOpsHistograms("compaction").count > 0)
        assert(metrics.nativeOpsMetrics("totalBytesReadByCompaction") > 0)
        assert(metrics.nativeOpsMetrics("totalBytesWrittenByCompaction") > 0)
        assert(metrics.pinnedBlocksMemUsage >= 0)
      }
    }
  }

  // Add tests to check valid and invalid values for max_open_files passed to the underlying
  // RocksDB instance.
  Seq("-1", "100", "1000").foreach { maxOpenFiles =>
    testWithColumnFamilies(s"SPARK-39781: adding valid max_open_files=$maxOpenFiles " +
      "config property for RocksDB state store instance should succeed",
      TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
      withTempDir { dir =>
        val sqlConf = SQLConf.get.clone()
        sqlConf.setConfString("spark.sql.streaming.stateStore.rocksdb.maxOpenFiles", maxOpenFiles)
        val dbConf = RocksDBConf(StateStoreConf(sqlConf))
        assert(dbConf.maxOpenFiles === maxOpenFiles.toInt)

        val remoteDir = dir.getCanonicalPath
        withDB(remoteDir, conf = dbConf,
          useColumnFamilies = colFamiliesEnabled) { db =>
          // Do some DB ops
          db.load(0)
          db.put("a", "1")
          db.commit()
          assert(toStr(db.get("a")) === "1")
        }
      }
    }
  }

  Seq("test", "true").foreach { maxOpenFiles =>
    testWithColumnFamilies(s"SPARK-39781: adding invalid max_open_files=$maxOpenFiles config " +
      "property for RocksDB state store instance should fail",
      TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
      withTempDir { dir =>
        val ex = intercept[IllegalArgumentException] {
          val sqlConf = SQLConf.get.clone()
          sqlConf.setConfString("spark.sql.streaming.stateStore.rocksdb.maxOpenFiles",
            maxOpenFiles)
          val dbConf = RocksDBConf(StateStoreConf(sqlConf))
          assert(dbConf.maxOpenFiles === maxOpenFiles.toInt)

          val remoteDir = dir.getCanonicalPath
          withDB(remoteDir, conf = dbConf, useColumnFamilies = colFamiliesEnabled) { db =>
            // Do some DB ops
            db.load(0)
            db.put("a", "1")
            db.commit()
            assert(toStr(db.get("a")) === "1")
          }
        }
        assert(ex.getMessage.contains("Invalid value for"))
        assert(ex.getMessage.contains("must be an integer"))
      }
    }
  }

  Seq("1", "2", "3").foreach { maxWriteBufferNumber =>
    Seq("16", "32", "64").foreach {writeBufferSizeMB =>
      testWithColumnFamilies(s"SPARK-42819: configure memtable memory usage with " +
        s"maxWriteBufferNumber=$maxWriteBufferNumber and writeBufferSize=$writeBufferSizeMB",
        TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
        withTempDir { dir =>
          val sqlConf = new SQLConf
          sqlConf.setConfString("spark.sql.streaming.stateStore.rocksdb.maxWriteBufferNumber",
            maxWriteBufferNumber)
          sqlConf.setConfString("spark.sql.streaming.stateStore.rocksdb.writeBufferSizeMB",
            writeBufferSizeMB)
          val dbConf = RocksDBConf(StateStoreConf(sqlConf))
          assert(dbConf.maxWriteBufferNumber === maxWriteBufferNumber.toInt)
          assert(dbConf.writeBufferSizeMB === writeBufferSizeMB.toInt)

          val remoteDir = dir.getCanonicalPath
          withDB(remoteDir, conf = dbConf, useColumnFamilies = colFamiliesEnabled) { db =>
            // Do some DB ops
            db.load(0)
            db.put("a", "1")
            db.commit()
            assert(toStr(db.get("a")) === "1")
          }
        }
      }
    }
  }

  testWithColumnFamilies("Verify that fallocate is allowed by default",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
     val sqlConf = new SQLConf
     val dbConf = RocksDBConf(StateStoreConf(sqlConf))
     assert(dbConf.allowFAllocate == true)
  }

 /** RocksDB memory management tests for bounded memory usage */
  testWithColumnFamilies("Memory mgmt - invalid config",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    withTempDir { dir =>
      try {
        RocksDBMemoryManager.resetWriteBufferManagerAndCache
        val sqlConf = new SQLConf
        sqlConf.setConfString(RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + "."
          + RocksDBConf.BOUNDED_MEMORY_USAGE_CONF_KEY, "true")
        sqlConf.setConfString(RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + "."
          + RocksDBConf.MAX_MEMORY_USAGE_MB_CONF_KEY, "100")
        sqlConf.setConfString(RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + "."
          + RocksDBConf.WRITE_BUFFER_CACHE_RATIO_CONF_KEY, "0.7")
        sqlConf.setConfString(RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + "."
          + RocksDBConf.HIGH_PRIORITY_POOL_RATIO_CONF_KEY, "0.6")

        val dbConf = RocksDBConf(StateStoreConf(sqlConf))
        assert(dbConf.boundedMemoryUsage === true)
        assert(dbConf.totalMemoryUsageMB === 100)
        assert(dbConf.writeBufferCacheRatio === 0.7)
        assert(dbConf.highPriorityPoolRatio === 0.6)

        val ex = intercept[Exception] {
          val remoteDir = dir.getCanonicalPath
          withDB(remoteDir, conf = dbConf, useColumnFamilies = colFamiliesEnabled) { db =>
            db.load(0)
            db.put("a", "1")
            db.commit()
          }
        }
        assert(ex.isInstanceOf[IllegalArgumentException])
        assert(ex.getMessage.contains("should be less than 1.0"))
      } finally {
        RocksDBMemoryManager.resetWriteBufferManagerAndCache
      }
    }
  }

  Seq("true", "false").foreach { boundedMemoryUsage =>
    testWithColumnFamilies(s"Memory mgmt - Cache reuse for RocksDB " +
      s"with boundedMemoryUsage=$boundedMemoryUsage",
      TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
      withTempDir { dir1 =>
        withTempDir { dir2 =>
          try {
            val sqlConf = new SQLConf
            sqlConf.setConfString(RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + "."
              + RocksDBConf.BOUNDED_MEMORY_USAGE_CONF_KEY, boundedMemoryUsage)

            val dbConf = RocksDBConf(StateStoreConf(sqlConf))
            assert(dbConf.boundedMemoryUsage === boundedMemoryUsage.toBoolean)

            val remoteDir1 = dir1.getCanonicalPath
            val (writeManager1, cache1) = withDB(remoteDir1,
              conf = dbConf,
              useColumnFamilies = colFamiliesEnabled) { db =>
              db.load(0)
              db.put("a", "1")
              db.commit()
              db.getWriteBufferManagerAndCache()
            }

            val remoteDir2 = dir2.getCanonicalPath
            val (writeManager2, cache2) = withDB(remoteDir2,
              conf = dbConf,
              useColumnFamilies = colFamiliesEnabled) { db =>
              db.load(0)
              db.put("a", "1")
              db.commit()
              db.getWriteBufferManagerAndCache()
            }

            if (boundedMemoryUsage == "true") {
              assert(writeManager1 === writeManager2)
              assert(cache1 === cache2)
            } else {
              assert(writeManager1 === null)
              assert(writeManager2 === null)
              assert(cache1 != cache2)
            }
          } finally {
            RocksDBMemoryManager.resetWriteBufferManagerAndCache
          }
        }
      }
    }
  }

  Seq("100", "1000", "100000").foreach { totalMemorySizeMB =>
    testWithColumnFamilies(s"Memory mgmt - valid config " +
      s"with totalMemorySizeMB=$totalMemorySizeMB",
      TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
      withTempDir { dir =>
        try {
          val sqlConf = new SQLConf
          sqlConf.setConfString(RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + "."
            + RocksDBConf.BOUNDED_MEMORY_USAGE_CONF_KEY, "true")
          sqlConf.setConfString(RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + "."
            + RocksDBConf.MAX_MEMORY_USAGE_MB_CONF_KEY, totalMemorySizeMB)
          sqlConf.setConfString(RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + "."
            + RocksDBConf.WRITE_BUFFER_CACHE_RATIO_CONF_KEY, "0.4")
          sqlConf.setConfString(RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + "."
            + RocksDBConf.HIGH_PRIORITY_POOL_RATIO_CONF_KEY, "0.1")

          val dbConf = RocksDBConf(StateStoreConf(sqlConf))
          assert(dbConf.boundedMemoryUsage === true)
          assert(dbConf.totalMemoryUsageMB === totalMemorySizeMB.toLong)
          assert(dbConf.writeBufferCacheRatio === 0.4)
          assert(dbConf.highPriorityPoolRatio === 0.1)

          val remoteDir = dir.getCanonicalPath
          withDB(remoteDir, conf = dbConf, useColumnFamilies = colFamiliesEnabled) { db =>
            db.load(0)
            db.put("a", "1")
            db.put("b", "2")
            db.remove("a")
            db.put("c", "3")
            db.commit()
          }
        } finally {
          RocksDBMemoryManager.resetWriteBufferManagerAndCache
        }
      }
    }
  }

  testWithColumnFamilies("SPARK-37224: flipping option 'trackTotalNumberOfRows' during restart",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    withTempDir { dir =>
      val remoteDir = dir.getCanonicalPath

      var curVersion: Long = 0
      // starting with the config "trackTotalNumberOfRows = true"
      // this should track the number of rows correctly
      withDB(remoteDir,
        conf = dbConf.copy(trackTotalNumberOfRows = true),
        useColumnFamilies = colFamiliesEnabled) { db =>
        db.load(curVersion)
        db.put("a", "5")
        db.put("b", "5")

        curVersion = db.commit()

        assert(db.metricsOpt.get.numUncommittedKeys === 2)
        assert(db.metricsOpt.get.numCommittedKeys === 2)
      }

      // restart with config "trackTotalNumberOfRows = false"
      // this should reset the number of keys as -1, and keep the number as -1
      withDB(remoteDir,
        conf = dbConf.copy(trackTotalNumberOfRows = false),
        useColumnFamilies = colFamiliesEnabled) { db =>
        db.load(curVersion)

        db.put("b", "7")
        db.put("c", "7")

        curVersion = db.commit()

        assert(db.metricsOpt.get.numUncommittedKeys === -1)
        assert(db.metricsOpt.get.numCommittedKeys === -1)
      }

      // restart with config "trackTotalNumberOfRows = true" again
      // this should count the number of keys at the load phase, and continue tracking the number
      withDB(remoteDir,
        conf = dbConf.copy(trackTotalNumberOfRows = true),
        useColumnFamilies = colFamiliesEnabled) { db =>
        db.load(curVersion)

        db.put("c", "8")
        db.put("d", "8")

        curVersion = db.commit()

        assert(db.metricsOpt.get.numUncommittedKeys === 4)
        assert(db.metricsOpt.get.numCommittedKeys === 4)
      }
    }
  }

  test("time travel - validate successful RocksDB load") {
    val remoteDir = Utils.createTempDir().toString
    val conf = dbConf.copy(minDeltasForSnapshot = 1, compactOnCommit = false)
    new File(remoteDir).delete() // to make sure that the directory gets created
    withDB(remoteDir, conf = conf) { db =>
      for (version <- 0 to 1) {
        db.load(version)
        db.put(version.toString, version.toString)
        db.commit()
      }
      // upload snapshot 2.zip
      db.doMaintenance()
      for (version <- Seq(2)) {
        db.load(version)
        db.put(version.toString, version.toString)
        db.commit()
      }
      // upload snapshot 3.zip
      db.doMaintenance()
      // simulate db in another executor that override the zip file
      withDB(remoteDir, conf = conf) { db1 =>
        for (version <- 0 to 1) {
          db1.load(version)
          db1.put(version.toString, version.toString)
          db1.commit()
        }
        db1.doMaintenance()
      }
      db.load(2)
      for (version <- Seq(2)) {
        db.load(version)
        db.put(version.toString, version.toString)
        db.commit()
      }
      // upload snapshot 3.zip
      db.doMaintenance()
      // rollback to version 2
      db.load(2)
    }
  }

  test("time travel 2 - validate successful RocksDB load") {
    Seq(1, 2).map(minDeltasForSnapshot => {
      val remoteDir = Utils.createTempDir().toString
      val conf = dbConf.copy(minDeltasForSnapshot = minDeltasForSnapshot,
        compactOnCommit = false)
      new File(remoteDir).delete() // to make sure that the directory gets created
      withDB(remoteDir, conf = conf) { db =>
        for (version <- 0 to 1) {
          db.load(version)
          db.put(version.toString, version.toString)
          db.commit()
        }
        // upload snapshot 2.zip
        db.doMaintenance()
        for (version <- 2 to 3) {
          db.load(version)
          db.put(version.toString, version.toString)
          db.commit()
        }
        db.load(0)
        // simulate db in another executor that override the zip file
        withDB(remoteDir, conf = conf) { db1 =>
          for (version <- 0 to 1) {
            db1.load(version)
            db1.put(version.toString, version.toString)
            db1.commit()
          }
          db1.doMaintenance()
        }
        for (version <- 2 to 3) {
          db.load(version)
          db.put(version.toString, version.toString)
          db.commit()
        }
        // upload snapshot 4.zip
        db.doMaintenance()
      }
      withDB(remoteDir, version = 4, conf = conf) { db =>
      }
    })
  }

  test("time travel 3 - validate successful RocksDB load") {
    val remoteDir = Utils.createTempDir().toString
    val conf = dbConf.copy(minDeltasForSnapshot = 0, compactOnCommit = false)
    new File(remoteDir).delete() // to make sure that the directory gets created
    withDB(remoteDir, conf = conf) { db =>
      for (version <- 0 to 2) {
        db.load(version)
        db.put(version.toString, version.toString)
        db.commit()
      }
      // upload snapshot 2.zip
      db.doMaintenance()
      for (version <- 1 to 3) {
        db.load(version)
        db.put(version.toString, version.toString)
        db.commit()
      }
      // upload snapshot 4.zip
      db.doMaintenance()
    }

    withDB(remoteDir, version = 4, conf = conf) { db =>
    }
  }

  test("validate Rocks DB SST files do not have a VersionIdMismatch" +
    " when metadata file is not overwritten - scenario 1") {
    val fmClass = "org.apache.spark.sql.execution.streaming.state." +
      "NoOverwriteFileSystemBasedCheckpointFileManager"
    withTempDir { dir =>
      val dbConf = RocksDBConf(StateStoreConf(new SQLConf()))
      val hadoopConf = new Configuration()
      hadoopConf.set(STREAMING_CHECKPOINT_FILE_MANAGER_CLASS.parent.key, fmClass)

      val remoteDir = dir.getCanonicalPath
      withDB(remoteDir, conf = dbConf, hadoopConf = hadoopConf) { db1 =>
        withDB(remoteDir, conf = dbConf, hadoopConf = hadoopConf) { db2 =>
          // commit version 1 via db1
          db1.load(0)
          db1.put("a", "1")
          db1.put("b", "1")

          db1.commit()

          // commit version 1 via db2
          db2.load(0)
          db2.put("a", "1")
          db2.put("b", "1")

          db2.commit()

          // commit version 2 via db2
          db2.load(1)
          db2.put("a", "2")
          db2.put("b", "2")

          db2.commit()

          // reload version 1, this should succeed
          db2.load(1)
          db1.load(1)

          // reload version 2, this should succeed
          db2.load(2)
          db1.load(2)
        }
      }
    }
  }

  test("validate Rocks DB SST files do not have a VersionIdMismatch" +
    " when metadata file is overwritten - scenario 1") {
    withTempDir { dir =>
      val dbConf = RocksDBConf(StateStoreConf(new SQLConf()))
      val hadoopConf = new Configuration()
      val remoteDir = dir.getCanonicalPath
      withDB(remoteDir, conf = dbConf, hadoopConf = hadoopConf) { db1 =>
        withDB(remoteDir, conf = dbConf, hadoopConf = hadoopConf) { db2 =>
          // commit version 1 via db1
          db1.load(0)
          db1.put("a", "1")
          db1.put("b", "1")

          db1.commit()

          // commit version 1 via db2
          db2.load(0)
          db2.put("a", "1")
          db2.put("b", "1")

          db2.commit()

          // commit version 2 via db2
          db2.load(1)
          db2.put("a", "2")
          db2.put("b", "2")

          db2.commit()

          // reload version 1, this should succeed
          db2.load(1)
          db1.load(1)

          // reload version 2, this should succeed
          db2.load(2)
          db1.load(2)
        }
      }
    }
  }

  test("validate Rocks DB SST files do not have a VersionIdMismatch" +
    " when metadata file is not overwritten - scenario 2") {
    val fmClass = "org.apache.spark.sql.execution.streaming.state." +
      "NoOverwriteFileSystemBasedCheckpointFileManager"
    withTempDir { dir =>
      val dbConf = RocksDBConf(StateStoreConf(new SQLConf()))
      val hadoopConf = new Configuration()
      hadoopConf.set(STREAMING_CHECKPOINT_FILE_MANAGER_CLASS.parent.key, fmClass)

      val remoteDir = dir.getCanonicalPath
      withDB(remoteDir, conf = dbConf, hadoopConf = hadoopConf) { db1 =>
        withDB(remoteDir, conf = dbConf, hadoopConf = hadoopConf) { db2 =>
          // commit version 1 via db2
          db2.load(0)
          db2.put("a", "1")
          db2.put("b", "1")

          db2.commit()

          // commit version 1 via db1
          db1.load(0)
          db1.put("a", "1")
          db1.put("b", "1")

          db1.commit()

          // commit version 2 via db2
          db2.load(1)
          db2.put("a", "2")
          db2.put("b", "2")

          db2.commit()

          // reload version 1, this should succeed
          db2.load(1)
          db1.load(1)

          // reload version 2, this should succeed
          db2.load(2)
          db1.load(2)
        }
      }
    }
  }

  test("validate Rocks DB SST files do not have a VersionIdMismatch" +
    " when metadata file is overwritten - scenario 2") {
    withTempDir { dir =>
      val dbConf = RocksDBConf(StateStoreConf(new SQLConf()))
      val hadoopConf = new Configuration()
      val remoteDir = dir.getCanonicalPath
      withDB(remoteDir, conf = dbConf, hadoopConf = hadoopConf) { db1 =>
        withDB(remoteDir, conf = dbConf, hadoopConf = hadoopConf) { db2 =>
          // commit version 1 via db2
          db2.load(0)
          db2.put("a", "1")
          db2.put("b", "1")

          db2.commit()

          // commit version 1 via db1
          db1.load(0)
          db1.put("a", "1")
          db1.put("b", "1")

          db1.commit()

          // commit version 2 via db2
          db2.load(1)
          db2.put("a", "2")
          db2.put("b", "2")

          db2.commit()

          // reload version 1, this should succeed
          db2.load(1)
          db1.load(1)

          // reload version 2, this should succeed
          db2.load(2)
          db1.load(2)
        }
      }
    }
  }

  test("ensure local files deleted on filesystem" +
    " are cleaned from dfs file mapping") {
    def getSSTFiles(dir: File): Set[File] = {
      val sstFiles = new mutable.HashSet[File]()
      dir.listFiles().foreach { f =>
        if (f.isDirectory) {
          sstFiles ++= getSSTFiles(f)
        } else {
          if (f.getName.endsWith(".sst")) {
            sstFiles.add(f)
          }
        }
      }
      sstFiles.toSet
    }

    def filterAndDeleteSSTFiles(dir: File, filesToKeep: Set[File]): Unit = {
      dir.listFiles().foreach { f =>
        if (f.isDirectory) {
          filterAndDeleteSSTFiles(f, filesToKeep)
        } else {
          if (!filesToKeep.contains(f) && f.getName.endsWith(".sst")) {
            logInfo(s"deleting ${f.getAbsolutePath} from local directory")
            f.delete()
          }
        }
      }
    }

    withTempDir { dir =>
      withTempDir { localDir =>
        val sqlConf = new SQLConf()
        val dbConf = RocksDBConf(StateStoreConf(sqlConf))
        logInfo(s"config set to ${dbConf.compactOnCommit}")
        val hadoopConf = new Configuration()
        val remoteDir = dir.getCanonicalPath
        withDB(remoteDir = remoteDir,
          conf = dbConf,
          hadoopConf = hadoopConf,
          localDir = localDir) { db =>
          db.load(0)
          db.put("a", "1")
          db.put("b", "1")
          db.commit()
          db.doMaintenance()

          // find all SST files written in version 1
          val sstFiles = getSSTFiles(localDir)

          // make more commits, this would generate more SST files and write
          // them to remoteDir
          for (version <- 1 to 10) {
            db.load(version)
            db.put("c", "1")
            db.put("d", "1")
            db.commit()
            db.doMaintenance()
          }

          // clean the SST files committed after version 1 from local
          // filesystem. This is similar to what a process like compaction
          // where multiple L0 SST files can be merged into a single L1 file
          filterAndDeleteSSTFiles(localDir, sstFiles)

          // reload 2, and overwrite commit for version 3, this should not
          // reuse any locally deleted files as they should be removed from the mapping
          db.load(2)
          db.put("e", "1")
          db.put("f", "1")
          db.commit()
          db.doMaintenance()

          // clean local state
          db.load(0)

          // reload version 3, should be successful
          db.load(3)
        }
      }
    }
  }

  private def sqlConf = SQLConf.get.clone()

  private def dbConf = RocksDBConf(StateStoreConf(sqlConf))

  def withDB[T](
      remoteDir: String,
      version: Int = 0,
      conf: RocksDBConf = dbConf,
      hadoopConf: Configuration = new Configuration(),
      useColumnFamilies: Boolean = false,
      localDir: File = Utils.createTempDir())(
      func: RocksDB => T): T = {
    var db: RocksDB = null
    try {
      db = new RocksDB(
        remoteDir,
        conf = conf,
        localRootDir = localDir,
        hadoopConf = hadoopConf,
        loggingId = s"[Thread-${Thread.currentThread.getId}]",
        useColumnFamilies = useColumnFamilies
        )
      db.load(version)
      func(db)
    } finally {
      if (db != null) {
        db.close()
      }
    }
  }

  def generateFiles(dir: String, fileToLengths: Seq[(String, Int)]): Unit = {
    fileToLengths.foreach { case (fileName, length) =>
      val file = new File(dir, fileName)
      FileUtils.write(file, "a" * length, Charset.defaultCharset)
    }
  }

  def saveCheckpointFiles(
      fileManager: RocksDBFileManager,
      fileToLengths: Seq[(String, Int)],
      version: Int,
      numKeys: Int): Unit = {
    val checkpointDir = Utils.createTempDir().getAbsolutePath // local dir to create checkpoints
    generateFiles(checkpointDir, fileToLengths)
    fileManager.saveCheckpointToDfs(checkpointDir, version, numKeys)
  }

  def loadAndVerifyCheckpointFiles(
      fileManager: RocksDBFileManager,
      verificationDir: String,
      version: Int,
      expectedFiles: Seq[(String, Int)],
      expectedNumKeys: Int): Unit = {
    val metadata = fileManager.loadCheckpointFromDfs(version, verificationDir)
    val filesAndLengths =
      listFiles(verificationDir).map(f => f.getName -> f.length).toSet ++
      listFiles(verificationDir + "/archive").map(f => s"archive/${f.getName}" -> f.length()).toSet
    assert(filesAndLengths === expectedFiles.toSet)
    assert(metadata.numKeys === expectedNumKeys)
  }

  implicit def toFile(path: String): File = new File(path)

  implicit def toArray(str: String): Array[Byte] = if (str != null) str.getBytes else null

  implicit def toStr(bytes: Array[Byte]): String = if (bytes != null) new String(bytes) else null

  def toStr(kv: ByteArrayPair): (String, String) = (toStr(kv.key), toStr(kv.value))

  def iterator(db: RocksDB, colFamilyName: String = "default"):
    Iterator[(String, String)] = db.iterator(colFamilyName).map(toStr)

  def listFiles(file: File): Seq[File] = {
    if (!file.exists()) return Seq.empty
    file.listFiles.filter(file => !file.getName.endsWith("crc") && !file.isDirectory)
      .toImmutableArraySeq
  }

  def listFiles(file: String): Seq[File] = listFiles(new File(file))
}

object RocksDBSuite {
  @volatile var singleton: RocksDB = _

  def withSingletonDB[T](func: => T): T = {
    try {
      singleton = new RocksDB(
        dfsRootDir = Utils.createTempDir().getAbsolutePath,
        conf = RocksDBConf().copy(compactOnCommit = false, minVersionsToRetain = 100),
        hadoopConf = new Configuration(),
        loggingId = s"[Thread-${Thread.currentThread.getId}]")

      func
    } finally {
      if (singleton != null) {
        singleton.close()
        singleton = null
      }
    }
  }
}
