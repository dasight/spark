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

package org.apache.spark.sql

import scala.collection.immutable.Seq
import scala.collection.parallel.CollectionConverters.ImmutableIterableIsParallelizable
import scala.jdk.CollectionConverters.MapHasAsJava

import org.apache.spark.SparkException
import org.apache.spark.sql.catalyst.ExtendedAnalysisException
import org.apache.spark.sql.catalyst.util.CollationFactory
import org.apache.spark.sql.connector.{DatasourceV2SQLBase, FakeV2ProviderWithCustomSchema}
import org.apache.spark.sql.connector.catalog.{Identifier, InMemoryTable}
import org.apache.spark.sql.connector.catalog.CatalogV2Implicits.CatalogHelper
import org.apache.spark.sql.connector.catalog.CatalogV2Util.withDefaultOwnership
import org.apache.spark.sql.execution.adaptive.AdaptiveSparkPlanHelper
import org.apache.spark.sql.execution.aggregate.{HashAggregateExec, ObjectHashAggregateExec}
import org.apache.spark.sql.execution.joins.{BroadcastHashJoinExec, SortMergeJoinExec}
import org.apache.spark.sql.types.{StringType, StructField, StructType}

class CollationSuite extends DatasourceV2SQLBase with AdaptiveSparkPlanHelper {
  protected val v2Source = classOf[FakeV2ProviderWithCustomSchema].getName

  test("collate returns proper type") {
    Seq("utf8_binary", "utf8_binary_lcase", "unicode", "unicode_ci").foreach { collationName =>
      checkAnswer(sql(s"select 'aaa' collate $collationName"), Row("aaa"))
      val collationId = CollationFactory.collationNameToId(collationName)
      assert(sql(s"select 'aaa' collate $collationName").schema(0).dataType
        == StringType(collationId))
    }
  }

  test("collation name is case insensitive") {
    Seq("uTf8_BiNaRy", "uTf8_BiNaRy_Lcase", "uNicOde", "UNICODE_ci").foreach { collationName =>
      checkAnswer(sql(s"select 'aaa' collate $collationName"), Row("aaa"))
      val collationId = CollationFactory.collationNameToId(collationName)
      assert(sql(s"select 'aaa' collate $collationName").schema(0).dataType
        == StringType(collationId))
    }
  }

  test("collation expression returns name of collation") {
    Seq("utf8_binary", "utf8_binary_lcase", "unicode", "unicode_ci").foreach { collationName =>
      checkAnswer(
        sql(s"select collation('aaa' collate $collationName)"), Row(collationName.toUpperCase()))
    }
  }

  test("collate function syntax") {
    assert(sql(s"select collate('aaa', 'utf8_binary')").schema(0).dataType == StringType(0))
    assert(sql(s"select collate('aaa', 'utf8_binary_lcase')").schema(0).dataType == StringType(1))
  }

  test("collate function syntax invalid arg count") {
    Seq("'aaa','a','b'", "'aaa'", "", "'aaa'").foreach(args => {
      val paramCount = if (args == "") 0 else args.split(',').length.toString
      checkError(
        exception = intercept[AnalysisException] {
          sql(s"select collate($args)")
        },
        errorClass = "WRONG_NUM_ARGS.WITHOUT_SUGGESTION",
        sqlState = "42605",
        parameters = Map(
          "functionName" -> "`collate`",
          "expectedNum" -> "2",
          "actualNum" -> paramCount.toString,
          "docroot" -> "https://spark.apache.org/docs/latest"),
        context = ExpectedContext(fragment = s"collate($args)", start = 7, stop = 15 + args.length)
      )
    })
  }

  test("collate function invalid collation data type") {
    checkError(
      exception = intercept[AnalysisException](sql("select collate('abc', 123)")),
      errorClass = "UNEXPECTED_INPUT_TYPE",
      sqlState = "42K09",
      Map(
        "functionName" -> "`collate`",
        "paramIndex" -> "first",
        "inputSql" -> "\"123\"",
        "inputType" -> "\"INT\"",
        "requiredType" -> "\"STRING\""),
      context = ExpectedContext(fragment = s"collate('abc', 123)", start = 7, stop = 25)
    )
  }

  test("NULL as collation name") {
    checkError(
      exception = intercept[AnalysisException] {
        sql("select collate('abc', cast(null as string))") },
      errorClass = "DATATYPE_MISMATCH.UNEXPECTED_NULL",
      sqlState = "42K09",
      Map("exprName" -> "`collation`", "sqlExpr" -> "\"CAST(NULL AS STRING)\""),
      context = ExpectedContext(
        fragment = s"collate('abc', cast(null as string))", start = 7, stop = 42)
    )
  }

  test("collate function invalid input data type") {
    checkError(
      exception = intercept[ExtendedAnalysisException] { sql(s"select collate(1, 'UTF8_BINARY')") },
      errorClass = "DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE",
      sqlState = "42K09",
      parameters = Map(
        "sqlExpr" -> "\"collate(1)\"",
        "paramIndex" -> "first",
        "inputSql" -> "\"1\"",
        "inputType" -> "\"INT\"",
        "requiredType" -> "\"STRING\""),
      context = ExpectedContext(
        fragment = s"collate(1, 'UTF8_BINARY')", start = 7, stop = 31))
  }

  test("collation expression returns default collation") {
    checkAnswer(sql(s"select collation('aaa')"), Row("UTF8_BINARY"))
  }

  test("invalid collation name throws exception") {
    checkError(
      exception = intercept[SparkException] { sql("select 'aaa' collate UTF8_BS") },
      errorClass = "COLLATION_INVALID_NAME",
      sqlState = "42704",
      parameters = Map("proposal" -> "UTF8_BINARY", "collationName" -> "UTF8_BS"))
  }

  test("equality check respects collation") {
    Seq(
      ("utf8_binary", "aaa", "AAA", false),
      ("utf8_binary", "aaa", "aaa", true),
      ("utf8_binary_lcase", "aaa", "aaa", true),
      ("utf8_binary_lcase", "aaa", "AAA", true),
      ("utf8_binary_lcase", "aaa", "bbb", false),
      ("unicode", "aaa", "aaa", true),
      ("unicode", "aaa", "AAA", false),
      ("unicode_CI", "aaa", "aaa", true),
      ("unicode_CI", "aaa", "AAA", true),
      ("unicode_CI", "aaa", "bbb", false)
    ).foreach {
      case (collationName, left, right, expected) =>
        checkAnswer(
          sql(s"select '$left' collate $collationName = '$right' collate $collationName"),
          Row(expected))
        checkAnswer(
          sql(s"select collate('$left', '$collationName') = collate('$right', '$collationName')"),
          Row(expected))
    }
  }

  test("comparisons respect collation") {
    Seq(
      ("utf8_binary", "AAA", "aaa", true),
      ("utf8_binary", "aaa", "aaa", false),
      ("utf8_binary", "aaa", "BBB", false),
      ("utf8_binary_lcase", "aaa", "aaa", false),
      ("utf8_binary_lcase", "AAA", "aaa", false),
      ("utf8_binary_lcase", "aaa", "bbb", true),
      ("unicode", "aaa", "aaa", false),
      ("unicode", "aaa", "AAA", true),
      ("unicode", "aaa", "BBB", true),
      ("unicode_CI", "aaa", "aaa", false),
      ("unicode_CI", "aaa", "AAA", false),
      ("unicode_CI", "aaa", "bbb", true)
    ).foreach {
      case (collationName, left, right, expected) =>
        checkAnswer(
          sql(s"select '$left' collate $collationName < '$right' collate $collationName"),
          Row(expected))
        checkAnswer(
          sql(s"select collate('$left', '$collationName') < collate('$right', '$collationName')"),
          Row(expected))
    }
  }

  test("checkCollation throws exception for incompatible collationIds") {
    val left: String = "abc" // collate with 'UNICODE_CI'
    val leftCollationName: String = "UNICODE_CI";
    var right: String = null // collate with 'UNICODE'
    val rightCollationName: String = "UNICODE";
    // contains
    right = left.substring(1, 2);
    checkError(
      exception = intercept[ExtendedAnalysisException] {
        spark.sql(s"SELECT contains(collate('$left', '$leftCollationName')," +
          s"collate('$right', '$rightCollationName'))")
      },
      errorClass = "DATATYPE_MISMATCH.COLLATION_MISMATCH",
      sqlState = "42K09",
      parameters = Map(
        "collationNameLeft" -> s"$leftCollationName",
        "collationNameRight" -> s"$rightCollationName",
        "sqlExpr" -> "\"contains(collate(abc), collate(b))\""
      ),
      context = ExpectedContext(fragment =
        s"contains(collate('abc', 'UNICODE_CI'),collate('b', 'UNICODE'))",
        start = 7, stop = 68)
    )
    // startsWith
    right = left.substring(0, 1);
    checkError(
      exception = intercept[ExtendedAnalysisException] {
        spark.sql(s"SELECT startsWith(collate('$left', '$leftCollationName')," +
          s"collate('$right', '$rightCollationName'))")
      },
      errorClass = "DATATYPE_MISMATCH.COLLATION_MISMATCH",
      sqlState = "42K09",
      parameters = Map(
        "collationNameLeft" -> s"$leftCollationName",
        "collationNameRight" -> s"$rightCollationName",
        "sqlExpr" -> "\"startswith(collate(abc), collate(a))\""
      ),
      context = ExpectedContext(fragment =
        s"startsWith(collate('abc', 'UNICODE_CI'),collate('a', 'UNICODE'))",
        start = 7, stop = 70)
    )
    // endsWith
    right = left.substring(2, 3);
    checkError(
      exception = intercept[ExtendedAnalysisException] {
        spark.sql(s"SELECT endsWith(collate('$left', '$leftCollationName')," +
          s"collate('$right', '$rightCollationName'))")
      },
      errorClass = "DATATYPE_MISMATCH.COLLATION_MISMATCH",
      sqlState = "42K09",
      parameters = Map(
        "collationNameLeft" -> s"$leftCollationName",
        "collationNameRight" -> s"$rightCollationName",
        "sqlExpr" -> "\"endswith(collate(abc), collate(c))\""
      ),
      context = ExpectedContext(fragment =
        s"endsWith(collate('abc', 'UNICODE_CI'),collate('c', 'UNICODE'))",
        start = 7, stop = 68)
    )
  }

  case class CollationTestCase[R](left: String, right: String, collation: String, expectedResult: R)

  test("Support contains string expression with Collation") {
    // Supported collations
    val checks = Seq(
      CollationTestCase("", "", "UTF8_BINARY", true),
      CollationTestCase("c", "", "UTF8_BINARY", true),
      CollationTestCase("", "c", "UTF8_BINARY", false),
      CollationTestCase("abcde", "c", "UTF8_BINARY", true),
      CollationTestCase("abcde", "C", "UTF8_BINARY", false),
      CollationTestCase("abcde", "bcd", "UTF8_BINARY", true),
      CollationTestCase("abcde", "BCD", "UTF8_BINARY", false),
      CollationTestCase("abcde", "fgh", "UTF8_BINARY", false),
      CollationTestCase("abcde", "FGH", "UTF8_BINARY", false),
      CollationTestCase("", "", "UNICODE", true),
      CollationTestCase("c", "", "UNICODE", true),
      CollationTestCase("", "c", "UNICODE", false),
      CollationTestCase("abcde", "c", "UNICODE", true),
      CollationTestCase("abcde", "C", "UNICODE", false),
      CollationTestCase("abcde", "bcd", "UNICODE", true),
      CollationTestCase("abcde", "BCD", "UNICODE", false),
      CollationTestCase("abcde", "fgh", "UNICODE", false),
      CollationTestCase("abcde", "FGH", "UNICODE", false),
      CollationTestCase("", "", "UTF8_BINARY_LCASE", true),
      CollationTestCase("c", "", "UTF8_BINARY_LCASE", true),
      CollationTestCase("", "c", "UTF8_BINARY_LCASE", false),
      CollationTestCase("abcde", "c", "UTF8_BINARY_LCASE", true),
      CollationTestCase("abcde", "C", "UTF8_BINARY_LCASE", true),
      CollationTestCase("abcde", "bcd", "UTF8_BINARY_LCASE", true),
      CollationTestCase("abcde", "BCD", "UTF8_BINARY_LCASE", true),
      CollationTestCase("abcde", "fgh", "UTF8_BINARY_LCASE", false),
      CollationTestCase("abcde", "FGH", "UTF8_BINARY_LCASE", false),
      CollationTestCase("", "", "UNICODE_CI", true),
      CollationTestCase("c", "", "UNICODE_CI", true),
      CollationTestCase("", "c", "UNICODE_CI", false),
      CollationTestCase("abcde", "c", "UNICODE_CI", true),
      CollationTestCase("abcde", "C", "UNICODE_CI", true),
      CollationTestCase("abcde", "bcd", "UNICODE_CI", true),
      CollationTestCase("abcde", "BCD", "UNICODE_CI", true),
      CollationTestCase("abcde", "fgh", "UNICODE_CI", false),
      CollationTestCase("abcde", "FGH", "UNICODE_CI", false)
    )
    checks.foreach(testCase => {
      checkAnswer(sql(s"SELECT contains(collate('${testCase.left}', '${testCase.collation}')," +
        s"collate('${testCase.right}', '${testCase.collation}'))"), Row(testCase.expectedResult))
    })
  }

  test("Support startsWith string expression with Collation") {
    // Supported collations
    val checks = Seq(
      CollationTestCase("", "", "UTF8_BINARY", true),
      CollationTestCase("c", "", "UTF8_BINARY", true),
      CollationTestCase("", "c", "UTF8_BINARY", false),
      CollationTestCase("abcde", "a", "UTF8_BINARY", true),
      CollationTestCase("abcde", "A", "UTF8_BINARY", false),
      CollationTestCase("abcde", "abc", "UTF8_BINARY", true),
      CollationTestCase("abcde", "ABC", "UTF8_BINARY", false),
      CollationTestCase("abcde", "bcd", "UTF8_BINARY", false),
      CollationTestCase("abcde", "BCD", "UTF8_BINARY", false),
      CollationTestCase("", "", "UNICODE", true),
      CollationTestCase("c", "", "UNICODE", true),
      CollationTestCase("", "c", "UNICODE", false),
      CollationTestCase("abcde", "a", "UNICODE", true),
      CollationTestCase("abcde", "A", "UNICODE", false),
      CollationTestCase("abcde", "abc", "UNICODE", true),
      CollationTestCase("abcde", "ABC", "UNICODE", false),
      CollationTestCase("abcde", "bcd", "UNICODE", false),
      CollationTestCase("abcde", "BCD", "UNICODE", false),
      CollationTestCase("", "", "UTF8_BINARY_LCASE", true),
      CollationTestCase("c", "", "UTF8_BINARY_LCASE", true),
      CollationTestCase("", "c", "UTF8_BINARY_LCASE", false),
      CollationTestCase("abcde", "a", "UTF8_BINARY_LCASE", true),
      CollationTestCase("abcde", "A", "UTF8_BINARY_LCASE", true),
      CollationTestCase("abcde", "abc", "UTF8_BINARY_LCASE", true),
      CollationTestCase("abcde", "ABC", "UTF8_BINARY_LCASE", true),
      CollationTestCase("abcde", "bcd", "UTF8_BINARY_LCASE", false),
      CollationTestCase("abcde", "BCD", "UTF8_BINARY_LCASE", false),
      CollationTestCase("", "", "UNICODE_CI", true),
      CollationTestCase("c", "", "UNICODE_CI", true),
      CollationTestCase("", "c", "UNICODE_CI", false),
      CollationTestCase("abcde", "a", "UNICODE_CI", true),
      CollationTestCase("abcde", "A", "UNICODE_CI", true),
      CollationTestCase("abcde", "abc", "UNICODE_CI", true),
      CollationTestCase("abcde", "ABC", "UNICODE_CI", true),
      CollationTestCase("abcde", "bcd", "UNICODE_CI", false),
      CollationTestCase("abcde", "BCD", "UNICODE_CI", false)
    )
    checks.foreach(testCase => {
      checkAnswer(sql(s"SELECT startswith(collate('${testCase.left}', '${testCase.collation}')," +
        s"collate('${testCase.right}', '${testCase.collation}'))"), Row(testCase.expectedResult))
    })
  }

  test("Support endsWith string expression with Collation") {
    // Supported collations
    val checks = Seq(
      CollationTestCase("", "", "UTF8_BINARY", true),
      CollationTestCase("c", "", "UTF8_BINARY", true),
      CollationTestCase("", "c", "UTF8_BINARY", false),
      CollationTestCase("abcde", "e", "UTF8_BINARY", true),
      CollationTestCase("abcde", "E", "UTF8_BINARY", false),
      CollationTestCase("abcde", "cde", "UTF8_BINARY", true),
      CollationTestCase("abcde", "CDE", "UTF8_BINARY", false),
      CollationTestCase("abcde", "bcd", "UTF8_BINARY", false),
      CollationTestCase("abcde", "BCD", "UTF8_BINARY", false),
      CollationTestCase("", "", "UNICODE", true),
      CollationTestCase("c", "", "UNICODE", true),
      CollationTestCase("", "c", "UNICODE", false),
      CollationTestCase("abcde", "e", "UNICODE", true),
      CollationTestCase("abcde", "E", "UNICODE", false),
      CollationTestCase("abcde", "cde", "UNICODE", true),
      CollationTestCase("abcde", "CDE", "UNICODE", false),
      CollationTestCase("abcde", "bcd", "UNICODE", false),
      CollationTestCase("abcde", "BCD", "UNICODE", false),
      CollationTestCase("", "", "UTF8_BINARY_LCASE", true),
      CollationTestCase("c", "", "UTF8_BINARY_LCASE", true),
      CollationTestCase("", "c", "UTF8_BINARY_LCASE", false),
      CollationTestCase("abcde", "e", "UTF8_BINARY_LCASE", true),
      CollationTestCase("abcde", "E", "UTF8_BINARY_LCASE", true),
      CollationTestCase("abcde", "cde", "UTF8_BINARY_LCASE", true),
      CollationTestCase("abcde", "CDE", "UTF8_BINARY_LCASE", true),
      CollationTestCase("abcde", "bcd", "UTF8_BINARY_LCASE", false),
      CollationTestCase("abcde", "BCD", "UTF8_BINARY_LCASE", false),
      CollationTestCase("", "", "UNICODE_CI", true),
      CollationTestCase("c", "", "UNICODE_CI", true),
      CollationTestCase("", "c", "UNICODE_CI", false),
      CollationTestCase("abcde", "e", "UNICODE_CI", true),
      CollationTestCase("abcde", "E", "UNICODE_CI", true),
      CollationTestCase("abcde", "cde", "UNICODE_CI", true),
      CollationTestCase("abcde", "CDE", "UNICODE_CI", true),
      CollationTestCase("abcde", "bcd", "UNICODE_CI", false),
      CollationTestCase("abcde", "BCD", "UNICODE_CI", false)
    )
    checks.foreach(testCase => {
      checkAnswer(sql(s"SELECT endswith(collate('${testCase.left}', '${testCase.collation}')," +
        s"collate('${testCase.right}', '${testCase.collation}'))"), Row(testCase.expectedResult))
    })
  }

  test("aggregates count respects collation") {
    Seq(
      ("utf8_binary", Seq("AAA", "aaa"), Seq(Row(1, "AAA"), Row(1, "aaa"))),
      ("utf8_binary", Seq("aaa", "aaa"), Seq(Row(2, "aaa"))),
      ("utf8_binary", Seq("aaa", "bbb"), Seq(Row(1, "aaa"), Row(1, "bbb"))),
      ("utf8_binary_lcase", Seq("aaa", "aaa"), Seq(Row(2, "aaa"))),
      ("utf8_binary_lcase", Seq("AAA", "aaa"), Seq(Row(2, "AAA"))),
      ("utf8_binary_lcase", Seq("aaa", "bbb"), Seq(Row(1, "aaa"), Row(1, "bbb"))),
      ("unicode", Seq("AAA", "aaa"), Seq(Row(1, "AAA"), Row(1, "aaa"))),
      ("unicode", Seq("aaa", "aaa"), Seq(Row(2, "aaa"))),
      ("unicode", Seq("aaa", "bbb"), Seq(Row(1, "aaa"), Row(1, "bbb"))),
      ("unicode_CI", Seq("aaa", "aaa"), Seq(Row(2, "aaa"))),
      ("unicode_CI", Seq("AAA", "aaa"), Seq(Row(2, "AAA"))),
      ("unicode_CI", Seq("aaa", "bbb"), Seq(Row(1, "aaa"), Row(1, "bbb")))
    ).foreach {
      case (collationName: String, input: Seq[String], expected: Seq[Row]) =>
        checkAnswer(sql(
          s"""
          with t as (
          select collate(col1, '$collationName') as c
          from
          values ${input.map(s => s"('$s')").mkString(", ")}
        )
        SELECT COUNT(*), c FROM t GROUP BY c
        """), expected)
    }
  }

  test("hash agg is not used for non binary collations") {
    val tableNameNonBinary = "T_NON_BINARY"
    val tableNameBinary = "T_BINARY"
    withTable(tableNameNonBinary) {
      withTable(tableNameBinary) {
        sql(s"CREATE TABLE $tableNameNonBinary (c STRING COLLATE UTF8_BINARY_LCASE) USING PARQUET")
        sql(s"INSERT INTO $tableNameNonBinary VALUES ('aaa')")
        sql(s"CREATE TABLE $tableNameBinary (c STRING COLLATE UTF8_BINARY) USING PARQUET")
        sql(s"INSERT INTO $tableNameBinary VALUES ('aaa')")

        val dfNonBinary = sql(s"SELECT COUNT(*), c FROM $tableNameNonBinary GROUP BY c")
        assert(collectFirst(dfNonBinary.queryExecution.executedPlan) {
          case _: HashAggregateExec | _: ObjectHashAggregateExec => ()
        }.isEmpty)

        val dfBinary = sql(s"SELECT COUNT(*), c FROM $tableNameBinary GROUP BY c")
        assert(collectFirst(dfBinary.queryExecution.executedPlan) {
          case _: HashAggregateExec | _: ObjectHashAggregateExec => ()
        }.nonEmpty)
      }
    }
  }

  test("test concurrently generating collation keys") {
    // generating ICU sort keys is not thread-safe by default so this should fail
    // if we don't handle the concurrency properly on Collator level

    (0 to 10).foreach(_ => {
      val collator = CollationFactory.fetchCollation("UNICODE").collator

      (0 to 100).par.foreach { _ =>
        collator.getCollationKey("aaa")
      }
    })
  }

  test("text writing to parquet with collation enclosed with backticks") {
    withTempPath{ path =>
      sql(s"select 'a' COLLATE `UNICODE`").write.parquet(path.getAbsolutePath)

      checkAnswer(
        spark.read.parquet(path.getAbsolutePath),
        Row("a"))
    }
  }

  test("create table with collation") {
    val tableName = "parquet_dummy_tbl"
    val collationName = "UTF8_BINARY_LCASE"
    val collationId = CollationFactory.collationNameToId(collationName)

    withTable(tableName) {
      sql(
        s"""
           |CREATE TABLE $tableName (c1 STRING COLLATE $collationName)
           |USING PARQUET
           |""".stripMargin)

      sql(s"INSERT INTO $tableName VALUES ('aaa')")
      sql(s"INSERT INTO $tableName VALUES ('AAA')")

      checkAnswer(sql(s"SELECT DISTINCT COLLATION(c1) FROM $tableName"), Seq(Row(collationName)))
      assert(sql(s"select c1 FROM $tableName").schema.head.dataType == StringType(collationId))
    }
  }

  test("create table with collations inside a struct") {
    val tableName = "struct_collation_tbl"
    val collationName = "UTF8_BINARY_LCASE"
    val collationId = CollationFactory.collationNameToId(collationName)

    withTable(tableName) {
      sql(
        s"""
           |CREATE TABLE $tableName
           |(c1 STRUCT<name: STRING COLLATE $collationName, age: INT>)
           |USING PARQUET
           |""".stripMargin)

      sql(s"INSERT INTO $tableName VALUES (named_struct('name', 'aaa', 'id', 1))")
      sql(s"INSERT INTO $tableName VALUES (named_struct('name', 'AAA', 'id', 2))")

      checkAnswer(sql(s"SELECT DISTINCT collation(c1.name) FROM $tableName"),
        Seq(Row(collationName)))
      assert(sql(s"SELECT c1.name FROM $tableName").schema.head.dataType == StringType(collationId))
    }
  }

  test("add collated column with alter table") {
    val tableName = "alter_column_tbl"
    val defaultCollation = "UTF8_BINARY"
    val collationName = "UTF8_BINARY_LCASE"
    val collationId = CollationFactory.collationNameToId(collationName)

    withTable(tableName) {
      sql(
        s"""
           |CREATE TABLE $tableName (c1 STRING)
           |USING PARQUET
           |""".stripMargin)

      sql(s"INSERT INTO $tableName VALUES ('aaa')")
      sql(s"INSERT INTO $tableName VALUES ('AAA')")

      checkAnswer(sql(s"SELECT DISTINCT COLLATION(c1) FROM $tableName"),
        Seq(Row(defaultCollation)))

      sql(
        s"""
           |ALTER TABLE $tableName
           |ADD COLUMN c2 STRING COLLATE $collationName
           |""".stripMargin)

      sql(s"INSERT INTO $tableName VALUES ('aaa', 'aaa')")
      sql(s"INSERT INTO $tableName VALUES ('AAA', 'AAA')")

      checkAnswer(sql(s"SELECT DISTINCT COLLATION(c2) FROM $tableName"),
        Seq(Row(collationName)))
      assert(sql(s"select c2 FROM $tableName").schema.head.dataType == StringType(collationId))
    }
  }

  test("create v2 table with collation column") {
    val tableName = "testcat.table_name"
    val collationName = "UTF8_BINARY_LCASE"
    val collationId = CollationFactory.collationNameToId(collationName)

    withTable(tableName) {
      sql(
        s"""
           |CREATE TABLE $tableName (c1 string COLLATE $collationName)
           |USING $v2Source
           |""".stripMargin)

      val testCatalog = catalog("testcat").asTableCatalog
      val table = testCatalog.loadTable(Identifier.of(Array(), "table_name"))

      assert(table.name == tableName)
      assert(table.partitioning.isEmpty)
      assert(table.properties == withDefaultOwnership(Map("provider" -> v2Source)).asJava)
      assert(table.columns().head.dataType() == StringType(collationId))

      val rdd = spark.sparkContext.parallelize(table.asInstanceOf[InMemoryTable].rows)
      checkAnswer(spark.internalCreateDataFrame(rdd, table.schema), Seq.empty)

      sql(s"INSERT INTO $tableName VALUES ('a'), ('A')")

      checkAnswer(sql(s"SELECT DISTINCT COLLATION(c1) FROM $tableName"),
        Seq(Row(collationName)))
      assert(sql(s"select c1 FROM $tableName").schema.head.dataType == StringType(collationId))
    }
  }

  test("disable partition on collated string column") {
    def createTable(partitionColumns: String*): Unit = {
      val tableName = "test_partition_tbl"
      withTable(tableName) {
        sql(
          s"""
             |CREATE TABLE $tableName
             |(id INT, c1 STRING COLLATE UNICODE, c2 string)
             |USING parquet
             |PARTITIONED BY (${partitionColumns.mkString(",")})
             |""".stripMargin)
      }
    }

    // should work fine on non collated columns
    createTable("id")
    createTable("c2")
    createTable("id", "c2")

    Seq(Seq("c1"), Seq("c1", "id"), Seq("c1", "c2")).foreach { partitionColumns =>
      checkError(
        exception = intercept[AnalysisException] {
          createTable(partitionColumns: _*)
        },
        errorClass = "INVALID_PARTITION_COLUMN_DATA_TYPE",
        parameters = Map("type" -> "\"STRING COLLATE UNICODE\"")
      );
    }
  }

  test("shuffle respects collation") {
    val in = (('a' to 'z') ++ ('A' to 'Z')).map(_.toString * 3).map(Row.apply(_))

    val schema = StructType(StructField(
      "col",
      StringType(CollationFactory.collationNameToId("UTF8_BINARY_LCASE"))) :: Nil)
    val df = spark.createDataFrame(sparkContext.parallelize(in), schema)

    df.repartition(10, df.col("col")).foreachPartition(
      (rowIterator: Iterator[Row]) => {
        val partitionData = rowIterator.map(r => r.getString(0)).toArray
        partitionData.foreach(s => {
          // assert that both lower and upper case of the string are present in the same partition.
          assert(partitionData.contains(s.toLowerCase()))
          assert(partitionData.contains(s.toUpperCase()))
        })
    })
  }

  test("hash based joins not allowed for non-binary collated strings") {
    val in = (('a' to 'z') ++ ('A' to 'Z')).map(_.toString * 3).map(e => Row.apply(e, e))

    val schema = StructType(StructField(
      "col_non_binary",
      StringType(CollationFactory.collationNameToId("UTF8_BINARY_LCASE"))) ::
      StructField("col_binary", StringType) :: Nil)
    val df1 = spark.createDataFrame(sparkContext.parallelize(in), schema)

    // Binary collations are allowed to use hash join.
    assert(collectFirst(
      df1.hint("broadcast").join(df1, df1("col_binary") === df1("col_binary"))
        .queryExecution.executedPlan) {
      case _: BroadcastHashJoinExec => ()
    }.nonEmpty)

    // Even with hint broadcast, hash join is not used for non-binary collated strings.
    assert(collectFirst(
      df1.hint("broadcast").join(df1, df1("col_non_binary") === df1("col_non_binary"))
        .queryExecution.executedPlan) {
      case _: BroadcastHashJoinExec => ()
    }.isEmpty)

    // Instead they will default to sort merge join.
    assert(collectFirst(
      df1.hint("broadcast").join(df1, df1("col_non_binary") === df1("col_non_binary"))
        .queryExecution.executedPlan) {
      case _: SortMergeJoinExec => ()
    }.nonEmpty)
  }
}
