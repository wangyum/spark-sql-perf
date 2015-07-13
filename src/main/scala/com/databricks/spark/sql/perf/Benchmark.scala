/*
 * Copyright 2015 Databricks Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.databricks.spark.sql.perf

import org.apache.spark.sql.catalyst.analysis.UnresolvedRelation

import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path, FileSystem}
import org.apache.spark.sql.{DataFrame, SQLContext}

import org.apache.spark.sql.catalyst.plans.logical.Subquery

/**
 * A collection of queries that test a particular aspect of Spark SQL.
 *
 * @param sqlContext An existing SQLContext.
 */
abstract class Benchmark(@transient protected val sqlContext: SQLContext)
  extends Serializable {

  import sqlContext.implicits._

  val resultsLocation = "/spark/sql/performance"
  val resultsTableName = "sqlPerformance"

  def createResultsTable() = {
    sqlContext.sql(s"DROP TABLE $resultsTableName")
    sqlContext.createExternalTable(
      "sqlPerformance", "json", Map("path" -> (resultsLocation + "/*/")))
  }

  protected def sparkContext = sqlContext.sparkContext

  implicit def toOption[A](a: A) = Option(a)

  def currentConfiguration = BenchmarkConfiguration(
    sqlConf = sqlContext.getAllConfs,
    sparkConf = sparkContext.getConf.getAll.toMap,
    defaultParallelism = sparkContext.defaultParallelism)

  /**
   * A Variation represents a setting (e.g. the number of shuffle partitions or if tables
   * are cached in memory) that we want to change in a experiment run.
   * A Variation has three parts, `name`, `options`, and `setup`.
   * The `name` is the identifier of a Variation. `options` is a Seq of options that
   * will be used for a query. Basically, a query will be executed with every option
   * defined in the list of `options`. `setup` defines the needed action for every
   * option. For example, the following Variation is used to change the number of shuffle
   * partitions of a query. The name of the Variation is "shufflePartitions". There are
   * two options, 200 and 2000. The setup is used to set the value of property
   * "spark.sql.shuffle.partitions".
   *
   * {{{
   *   Variation("shufflePartitions", Seq("200", "2000")) {
   *     case num => sqlContext.setConf("spark.sql.shuffle.partitions", num)
   *   }
   * }}}
   */
  case class Variation[T](name: String, options: Seq[T])(val setup: T => Unit)

  /**
   * Starts an experiment run with a given set of queries.
   * @param queriesToRun Queries to be executed.
   * @param includeBreakdown If it is true, breakdown results of a query will be recorded.
   *                         Setting it to true may significantly increase the time used to
   *                         execute a query.
   * @param iterations The number of iterations.
   * @param variations [[Variation]]s used in this run.
   * @param tags Tags of this run.
   * @return It returns a ExperimentStatus object that can be used to
   *         track the progress of this experiment run.
   */
  def runExperiment(
      queriesToRun: Seq[Query],
      includeBreakdown: Boolean = false,
      iterations: Int = 3,
      variations: Seq[Variation[_]] = Seq(Variation("StandardRun", Seq("true")) { _ => {} }),
      tags: Map[String, String] = Map.empty) = {

    class ExperimentStatus {
      val currentResults = new collection.mutable.ArrayBuffer[BenchmarkResult]()
      val currentRuns = new collection.mutable.ArrayBuffer[ExperimentRun]()
      val currentMessages = new collection.mutable.ArrayBuffer[String]()

      // Stats for HTML status message.
      @volatile var currentQuery = ""
      @volatile var currentPlan = ""
      @volatile var currentConfig = ""
      @volatile var failures = 0
      @volatile var startTime = 0L

      def cartesianProduct[T](xss: List[List[T]]): List[List[T]] = xss match {
        case Nil => List(Nil)
        case h :: t => for(xh <- h; xt <- cartesianProduct(t)) yield xh :: xt
      }

      val timestamp = System.currentTimeMillis()
      val combinations = cartesianProduct(variations.map(l => (0 until l.options.size).toList).toList)
      val resultsFuture = Future {
        val results = (1 to iterations).flatMap { i =>
          combinations.map { setup =>
            val currentOptions = variations.asInstanceOf[Seq[Variation[Any]]].zip(setup).map {
              case (v, idx) =>
                v.setup(v.options(idx))
                v.name -> v.options(idx).toString
            }
            currentConfig = currentOptions.map { case (k,v) => s"$k: $v" }.mkString(", ")

            val result = ExperimentRun(
              timestamp = timestamp,
              iteration = i,
              tags = currentOptions.toMap ++ tags,
              configuration = currentConfiguration,
              queriesToRun.flatMap { q =>
                val setup = s"iteration: $i, ${currentOptions.map { case (k, v) => s"$k=$v"}.mkString(", ")}"
                currentMessages += s"Running query ${q.name} $setup"

                currentQuery = q.name
                currentPlan = q.newDataFrame().queryExecution.executedPlan.toString
                startTime = System.currentTimeMillis()

                val singleResult = q.benchmark(includeBreakdown, setup)
                singleResult.failure.foreach { f =>
                  failures += 1
                  currentMessages += s"Query '${q.name}' failed: ${f.message}"
                }
                singleResult.executionTime.foreach(time => currentMessages += s"Exec time: $time")
                currentResults += singleResult
                singleResult :: Nil
              })
            currentRuns += result

            result
          }
        }

        try {
          val resultsTable = sqlContext.createDataFrame(results)
          currentMessages += s"Results written to table: 'sqlPerformance' at $resultsLocation/$timestamp"
          results.toDF().write
            .format("json")
            .save(s"$resultsLocation/$timestamp")

          results.toDF()
        } catch {
          case e: Throwable => currentMessages += s"Failed to write data: $e"
        }
      }

      /** Waits for the finish of the experiment. */
      def waitForFinish(timeoutInSeconds: Int) = {
        Await.result(resultsFuture, timeoutInSeconds.seconds)
      }

      /** Returns results from an actively running experiment. */
      def getCurrentResults() = {
        val tbl = sqlContext.createDataFrame(currentResults)
        tbl.registerTempTable("currentResults")
        tbl
      }

      /** Returns full iterations from an actively running experiment. */
      def getCurrentRuns() = {
        val tbl = sqlContext.createDataFrame(currentRuns)
        tbl.registerTempTable("currentRuns")
        tbl
      }

      def tail(n: Int = 5) = {
        currentMessages.takeRight(n).mkString("\n")
      }

      def status =
        if (resultsFuture.isCompleted) {
          if (resultsFuture.value.get.isFailure) "Failed" else "Successful"
        } else {
          "Running"
        }

      override def toString =
        s"""Permalink: table("sqlPerformance").where('timestamp === ${timestamp}L)"""


      def html =
        s"""
           |<h2>$status Experiment</h2>
           |<b>Permalink:</b> <tt>table("$resultsTableName").where('timestamp === ${timestamp}L)</tt><br/>
           |<b>Iterations complete:</b> ${currentRuns.size / combinations.size} / $iterations<br/>
           |<b>Failures:</b> $failures<br/>
           |<b>Queries run:</b> ${currentResults.size} / ${iterations * combinations.size * queriesToRun.size}<br/>
           |<b>Run time:</b> ${(System.currentTimeMillis() - timestamp) / 1000}s<br/>
           |
           |<h2>Current Query: $currentQuery</h2>
           |Runtime: ${(System.currentTimeMillis() - startTime) / 1000}s
           |$currentConfig<br/>
           |<h3>QueryPlan</h3>
           |<pre>
           |${currentPlan.replaceAll("\n", "<br/>")}
           |</pre>
           |
           |<h2>Logs</h2>
           |<pre>
           |${tail()}
           |</pre>
         """.stripMargin
    }
    new ExperimentStatus
  }

  /** Factory object for benchmark queries. */
  object Query {
    def apply(
        name: String,
        sqlText: String,
        description: String,
        collectResults: Boolean = true): Query = {
      new Query(name, sqlContext.sql(sqlText), description, collectResults, Some(sqlText))
    }

    def apply(
        name: String,
        dataFrameBuilder: => DataFrame,
        description: String): Query = {
      new Query(name, dataFrameBuilder, description, true, None)
    }
  }

  /** Holds one benchmark query and its metadata. */
  class Query(
      val name: String,
      buildDataFrame: => DataFrame,
      val description: String,
      val collectResults: Boolean,
      val sqlText: Option[String]) {

    override def toString =
      s"""
         |== Query: $name ==
         |${buildDataFrame.queryExecution.analyzed}
       """.stripMargin

    val tablesInvolved = buildDataFrame.queryExecution.logical collect {
      case UnresolvedRelation(tableIdentifier, _) => {
        // We are ignoring the database name.
        tableIdentifier.last
      }
    }

    def newDataFrame() = buildDataFrame

    def benchmarkMs[A](f: => A): Double = {
      val startTime = System.nanoTime()
      val ret = f
      val endTime = System.nanoTime()
      (endTime - startTime).toDouble / 1000000
    }

    def benchmark(includeBreakdown: Boolean, description: String = "") = {
      try {
        val dataFrame = buildDataFrame
        sparkContext.setJobDescription(s"Query: $name, $description")
        val queryExecution = dataFrame.queryExecution
        // We are not counting the time of ScalaReflection.convertRowToScala.
        val parsingTime = benchmarkMs {
          queryExecution.logical
        }
        val analysisTime = benchmarkMs {
          queryExecution.analyzed
        }
        val optimizationTime = benchmarkMs {
          queryExecution.optimizedPlan
        }
        val planningTime = benchmarkMs {
          queryExecution.executedPlan
        }

        val breakdownResults = if (includeBreakdown) {
          val depth = queryExecution.executedPlan.treeString.split("\n").size
          val physicalOperators = (0 until depth).map(i => (i, queryExecution.executedPlan(i)))
          physicalOperators.map {
            case (index, node) =>
              val executionTime = benchmarkMs {
                node.execute().map(_.copy()).foreach(row => Unit)
              }
              BreakdownResult(node.nodeName, node.simpleString, index, executionTime)
          }
        } else {
          Seq.empty[BreakdownResult]
        }

        // The executionTime for the entire query includes the time of type conversion from catalyst to scala.
        val executionTime = if (collectResults) {
          benchmarkMs {
            dataFrame.rdd.collect()
          }
        } else {
          benchmarkMs {
            dataFrame.rdd.foreach { row => Unit }
          }
        }

        val joinTypes = dataFrame.queryExecution.executedPlan.collect {
          case k if k.nodeName contains "Join" => k.nodeName
        }

        BenchmarkResult(
          name = name,
          joinTypes = joinTypes,
          tables = tablesInvolved,
          parsingTime = parsingTime,
          analysisTime = analysisTime,
          optimizationTime = optimizationTime,
          planningTime = planningTime,
          executionTime = executionTime,
          queryExecution = dataFrame.queryExecution.toString,
          breakDown = breakdownResults)
      } catch {
        case e: Exception =>
           BenchmarkResult(
             name = name,
             failure = Failure(e.getClass.getName, e.getMessage))
      }
    }
  }
}