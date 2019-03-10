/**
  * Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
  *
  * Licensed under the Apache License, Version 2.0 (the "License"). You may not
  * use this file except in compliance with the License. A copy of the License
  * is located at
  *
  *     http://aws.amazon.com/apache2.0/
  *
  * or in the "license" file accompanying this file. This file is distributed on
  * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
  * express or implied. See the License for the specific language governing
  * permissions and limitations under the License.
  *
  */

package com.amazon.deequ.runtime.spark.executor

import com.amazon.deequ.metrics.Metric
import com.amazon.deequ.runtime.spark.operators.Operator
import com.amazon.deequ.serialization.json.SimpleResultSerde
//import com.amazon.deequ.serialization.json.SimpleResultSerde
import org.apache.spark.sql.{DataFrame, SparkSession}

/**
  * The result returned from AnalysisRunner and Analysis
  *
  * @param metricMap Analyzers and their Metric results
  */
case class OperatorResults(metricMap: Map[Operator[_, Metric[_]], Metric[_]]) {

  def allMetrics: Seq[Metric[_]] = {
    metricMap.values.toSeq
  }

  def ++(other: OperatorResults): OperatorResults = {
    OperatorResults(metricMap ++ other.metricMap)
  }

  def metric(analyzer: Operator[_, Metric[_]]): Option[Metric[_]] = {
    metricMap.get(analyzer)
  }
}

object OperatorResults {

  def empty: OperatorResults = OperatorResults(Map.empty)

  def successMetricsAsDataFrame(
    sparkSession: SparkSession,
    analyzerContext: OperatorResults,
    forAnalyzers: Seq[Operator[_, Metric[_]]] = Seq.empty)
  : DataFrame = {

    val metricsList = getSimplifiedMetricOutputForSelectedAnalyzers(analyzerContext, forAnalyzers)

    import sparkSession.implicits._

    metricsList.toDF("entity", "instance", "name", "value")
  }

  def successMetricsAsJson(analyzerContext: OperatorResults,
                           forAnalyzers: Seq[Operator[_, Metric[_]]] = Seq.empty): String = {

    val metricsList = getSimplifiedMetricOutputForSelectedAnalyzers(analyzerContext, forAnalyzers)

    val result = metricsList.map { simplifiedMetricOutput =>
      Map(
        "entity" -> simplifiedMetricOutput.entity,
        "instance" -> simplifiedMetricOutput.instance,
        "name" -> simplifiedMetricOutput.name,
        "value" -> simplifiedMetricOutput.value
      )
    }

    SimpleResultSerde.serialize(result)
  }

  private[this] def getSimplifiedMetricOutputForSelectedAnalyzers(
                                                                   analyzerContext: OperatorResults,
                                                                   forAnalyzers: Seq[Operator[_, Metric[_]]])
  : Seq[SimpleMetricOutput] = {

    val selectedMetrics = analyzerContext.metricMap
      .filterKeys(analyzer => forAnalyzers.isEmpty || forAnalyzers.contains(analyzer))
      .values
      .toSeq

    val metricsList = selectedMetrics
      .filter(_.value.isSuccess) // Get analyzers with successful results
      .flatMap(_.flatten()) // Get metrics as double
      .map { doubleMetric =>
      SimpleMetricOutput(
        doubleMetric.entity.toString,
        doubleMetric.instance,
        doubleMetric.name,
        doubleMetric.value.get
      )
    }

    metricsList
  }

  private[this] case class SimpleMetricOutput(
      entity: String,
      instance: String,
      name: String,
      value: Double)
}
