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

package com.holdenkarau.spark.testing

import java.io.File

import scala.math.abs
import scala.collection.mutable.HashMap

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.hive._
import org.apache.spark.sql.types.StructType
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.conf.HiveConf.ConfVars

import org.scalatest.{FunSuiteLike}

/**
 * :: Experimental ::
 * Base class for testing Spark DataFrames.
 */

trait DataFrameSuiteBase extends DataFrameSuiteBaseLike with SharedSparkContext {
  override def beforeAll() {
    super.beforeAll()
    super.beforeAllTestCases()
  }

  override def afterAll() {
    super.afterAllTestCases()
    super.afterAll()
  }

}

trait DataFrameSuiteBaseLike extends FunSuiteLike with SparkContextProvider with Serializable {

  @transient var _sqlContext: HiveContext = _

  def sqlContext: HiveContext = _sqlContext

  def beforeAllTestCases() {
    /** Constructs a configuration for hive, where the metastore is located in a temp directory. */
    def newTemporaryConfiguration(): Map[String, String] = {
      val tempDir = Utils.createTempDir()
      val localMetastore = new File(tempDir, "metastore")
      val propMap: HashMap[String, String] = HashMap()
      // We have to mask all properties in hive-site.xml that relates to metastore data source
      // as we used a local metastore here.
      HiveConf.ConfVars.values().foreach { confvar =>
        if (confvar.varname.contains("datanucleus") || confvar.varname.contains("jdo")
          || confvar.varname.contains("hive.metastore.rawstore.impl")) {
          propMap.put(confvar.varname, confvar.getDefaultExpr())
        }
      }
      propMap.put(HiveConf.ConfVars.METASTOREWAREHOUSE.varname, localMetastore.toURI.toString)
      propMap.put(HiveConf.ConfVars.METASTORECONNECTURLKEY.varname,
        s"jdbc:derby:;databaseName=${localMetastore.getAbsolutePath};create=true")
      propMap.put("datanucleus.rdbms.datastoreAdapterClassName",
        "org.datanucleus.store.rdbms.adapter.DerbyAdapter")

      // SPARK-11783: When "hive.metastore.uris" is set, the metastore connection mode will be
      // remote (https://cwiki.apache.org/confluence/display/Hive/AdminManual+MetastoreAdmin
      // mentions that "If hive.metastore.uris is empty local mode is assumed, remote otherwise").
      // Remote means that the metastore server is running in its own process.
      // When the mode is remote, configurations like "javax.jdo.option.ConnectionURL" will not be
      // used (because they are used by remote metastore server that talks to the database).
      // Because execution Hive should always connects to a embedded derby metastore.
      // We have to remove the value of hive.metastore.uris. So, the execution Hive client connects
      // to the actual embedded derby metastore instead of the remote metastore.
      // You can search HiveConf.ConfVars.METASTOREURIS in the code of HiveConf (in Hive's repo).
      // Then, you will find that the local metastore mode is only set to true when
      // hive.metastore.uris is not set.
      propMap.put(ConfVars.METASTOREURIS.varname, "")

      propMap.toMap
    }
    val config = newTemporaryConfiguration()
    class TestHiveContext(sc: SparkContext) extends HiveContext(sc) {
      override def configure(): Map[String, String] = config
    }
    _sqlContext = new HiveContext(sc)
  }

  def afterAllTestCases() {
    _sqlContext = null
  }

  /**
   * Compares if two [[DataFrame]]s are equal, checks the schema and then if that matches
   * checks if the rows are equal.
   */
  def equalDataFrames(expected: DataFrame, result: DataFrame) {
    equalSchema(expected.schema, result.schema)
    expected.rdd.cache()
    result.rdd.cache()
    val expectedRDD = zipWithIndex(expected.rdd)
    println("ExpectedRDD")
    expectedRDD.collect().foreach(x => println(x))

    val resultRDD = zipWithIndex(result.rdd)
    println("ResultRDD")
    resultRDD.foreach(x => println(x))

    assert(expectedRDD.count() == resultRDD.count())
    val unequal = expectedRDD.cogroup(resultRDD).filter{case (idx, (r1, r2)) =>
      !(r1.isEmpty || r2.isEmpty) &&
      !(r1.head.equals(r2.head) || DataFrameSuiteBase.approxEquals(r1.head, r2.head, 0.0))
    }.take(1)
    assert(unequal === List())
    expected.rdd.unpersist()
    result.rdd.unpersist()
  }


  /**
   * Zip RDD's with precise indexes. This is used so we can join two DataFrame's
   * Rows together regardless of if the source is different but still compare based on
   * the order.
   */
  private def zipWithIndex[T](input: RDD[T]): RDD[(Int, T)] = {
    val counts = input.mapPartitions{itr => Iterator(itr.size)}.collect()
    val countSums = counts.scanLeft(0)(_ + _).zipWithIndex.map{case (x, y) => (y, x)}.toMap
    input.mapPartitionsWithIndex{case (idx, itr) => itr.zipWithIndex.map{case (y, i) =>
      (i + countSums(idx), y)}
    }
  }

  /**
   * Compares if two [[DataFrame]]s are equal, checks that the schemas are the same.
   * When comparing inexact fields uses tol.
   */
  def approxEqualDataFrames(expected: DataFrame, result: DataFrame, tol: Double) {
    equalSchema(expected.schema, result.schema)
    expected.rdd.cache()
    result.rdd.cache()
    val expectedRDD = zipWithIndex(expected.rdd)
    val resultRDD = zipWithIndex(result.rdd)
    val cogrouped = expectedRDD.cogroup(resultRDD)
    val unequal = cogrouped.filter{case (idx, (r1, r2)) =>
      (r1.isEmpty || r2.isEmpty) || (
        !DataFrameSuiteBase.approxEquals(r1.head, r2.head, tol))
    }.take(1)
    expected.rdd.unpersist()
    result.rdd.unpersist()
    assert(unequal === List())
  }

  /**
   * Compares the schema
   */
  def equalSchema(expected: StructType, result: StructType): Unit = {
    assert(expected.treeString === result.treeString)
  }

  def approxEquals(r1: Row, r2: Row, tol: Double): Boolean = {
    DataFrameSuiteBase.approxEquals(r1, r2, tol)
  }
}

object DataFrameSuiteBase {

  /** Approximate equality, based on equals from [[Row]] */
  def approxEquals(r1: Row, r2: Row, tol: Double): Boolean = {
    if (r1.length != r2.length) {
      return false
    } else {
      var i = 0
      val length = r1.length
      while (i < length) {
        if (r1.isNullAt(i) != r2.isNullAt(i)) {
          return false
        }
        if (!r1.isNullAt(i)) {
          val o1 = r1.get(i)
          val o2 = r2.get(i)
          o1 match {
            case b1: Array[Byte] =>
              if (!o2.isInstanceOf[Array[Byte]] ||
                !java.util.Arrays.equals(b1, o2.asInstanceOf[Array[Byte]])) {
                return false
              }
            case f1: Float if java.lang.Float.isNaN(f1) =>
              if (!o2.isInstanceOf[Float] || ! java.lang.Float.isNaN(o2.asInstanceOf[Float])) {
                return false
              }
            case d1: Double if java.lang.Double.isNaN(d1) =>
              if (!o2.isInstanceOf[Double] || ! java.lang.Double.isNaN(o2.asInstanceOf[Double])) {
                return false
              }
            case d1: java.math.BigDecimal if o2.isInstanceOf[java.math.BigDecimal] =>
              if (d1.compareTo(o2.asInstanceOf[java.math.BigDecimal]) != 0) {
                return false
              }
            case f1: Float if o2.isInstanceOf[Float] =>
              if (abs(f1-o2.asInstanceOf[Float]) > tol) {
              return false
            }
            case d1: Double if o2.isInstanceOf[Double] =>
              if (abs(d1-o2.asInstanceOf[Double]) > tol) {
              return false
            }
            case _ => if (o1 != o2) {
              return false
            }
          }
        }
        i += 1
      }
    }
    true
  }
}
