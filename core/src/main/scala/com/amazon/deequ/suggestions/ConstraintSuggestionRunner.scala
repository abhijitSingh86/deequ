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

package com.amazon.deequ.suggestions

import com.amazon.deequ.{RepositoryOptions, VerificationResult, VerificationSuite}
import com.amazon.deequ.checks.{Check, CheckLevel}
import com.amazon.deequ.profiles.ColumnProfilerRunner
import com.amazon.deequ.repository.{MetricsRepository, ResultKey}
//import com.amazon.deequ.io.DfsUtils
import com.amazon.deequ.profiles.{ColumnProfile, ColumnProfiles}
//import com.amazon.deequ.repository.{MetricsRepository, ResultKey}
import com.amazon.deequ.runtime.{Dataset, Engine}
import com.amazon.deequ.suggestions.rules._


object Rules {

  val DEFAULT: Seq[ConstraintRule[ColumnProfile]] =
    Seq(CompleteIfCompleteRule(), RetainCompletenessRule(), RetainTypeRule(),
      CategoricalRangeRule(), FractionalCategoricalRangeRule(),
      NonNegativeNumbersRule())
}

//private[suggestions] case class ConstraintSuggestionFileOutputOptions(
//      session: Option[SparkSession],
//      saveColumnProfilesJsonToPath: Option[String],
//      saveConstraintSuggestionsJsonToPath: Option[String],
//      saveEvaluationResultsJsonToPath: Option[String],
//      overwriteResults: Boolean)

/**
  * Generate suggestions for constraints by applying the rules on the column profiles computed from
  * the data at hand.
  *
  */
//@Experimental
class ConstraintSuggestionRunner {

  def onData(data: Dataset, engine: Engine): ConstraintSuggestionRunBuilder = {
    new ConstraintSuggestionRunBuilder(data, engine)
  }

//  private[suggestions] def run(
  private[suggestions] def run(
      data: Dataset,
      engine: Engine,
      constraintRules: Seq[ConstraintRule[ColumnProfile]],
      restrictToColumns: Option[Seq[String]],
      lowCardinalityHistogramThreshold: Int,
      printStatusUpdates: Boolean,
      testsetRatio: Option[Double],
      testsetSplitRandomSeed: Option[Long],
      //cacheInputs: Boolean,
      //fileOutputOptions: ConstraintSuggestionFileOutputOptions,
      metricsRepositoryOptions: RepositoryOptions)
    : ConstraintSuggestionResult = {

    testsetRatio.foreach { testsetRatio =>
      require(testsetRatio > 0 && testsetRatio < 1.0, "Testset ratio must be in ]0, 1[")
    }

    val (trainingData, testData) = engine.splitTrainTestSets(data, testsetRatio, testsetSplitRandomSeed)

//    if (cacheInputs) {
//      trainingData.cache()
//      testData.foreach { _.cache() }
//    }

    val (columnProfiles, constraintSuggestions) = ConstraintSuggestionRunner().profileAndSuggest(
        trainingData,
        engine,
        constraintRules,
        restrictToColumns,
        lowCardinalityHistogramThreshold,
        printStatusUpdates,
        metricsRepositoryOptions
      )

//    saveColumnProfilesJsonToFileSystemIfNecessary(
//      fileOutputOptions,
//      printStatusUpdates,
//      columnProfiles
//    )
//
//    if (cacheInputs) {
//      trainingData.unpersist()
//    }
//
//    saveConstraintSuggestionJsonToFileSystemIfNecessary(
//      fileOutputOptions,
//      printStatusUpdates,
//      constraintSuggestions
//    )

    val verificationResult = evaluateConstraintsIfNecessary(
      testData,
      engine,
      printStatusUpdates,
      constraintSuggestions
//fileOutputOptions
    )

    val columnsWithSuggestions = constraintSuggestions
      .map(suggestion => suggestion.columnName -> suggestion)
      .groupBy { case (columnName, _) => columnName }
      .mapValues { groupedSuggestionsWithColumnNames =>
        groupedSuggestionsWithColumnNames.map { case (_, suggestion) => suggestion } }

    ConstraintSuggestionResult(columnProfiles.profiles, columnsWithSuggestions, verificationResult)
  }


  private[suggestions] def profileAndSuggest(
      trainingData: Dataset,
      engine: Engine,
      constraintRules: Seq[ConstraintRule[ColumnProfile]],
      restrictToColumns: Option[Seq[String]],
      lowCardinalityHistogramThreshold: Int,
      printStatusUpdates: Boolean,
      metricsRepositoryOptions: RepositoryOptions
    ): (ColumnProfiles, Seq[ConstraintSuggestion]) = {

    var columnProfilerRunner = ColumnProfilerRunner()
      .onData(trainingData, engine)
      .printStatusUpdates(printStatusUpdates)
      .withLowCardinalityHistogramThreshold(lowCardinalityHistogramThreshold)

    restrictToColumns.foreach { restrictToColumns =>
      columnProfilerRunner = columnProfilerRunner.restrictToColumns(restrictToColumns)
    }

    metricsRepositoryOptions.metricsRepository.foreach { metricsRepository =>
      var columnProfilerRunnerWithRepository = columnProfilerRunner.useRepository(metricsRepository)

      metricsRepositoryOptions.reuseExistingResultsForKey.foreach { reuseExistingResultsKey =>
        columnProfilerRunnerWithRepository = columnProfilerRunnerWithRepository
          .reuseExistingResultsForKey(reuseExistingResultsKey,
            metricsRepositoryOptions.failIfResultsForReusingMissing)
      }

      metricsRepositoryOptions.saveOrAppendResultsWithKey.foreach { saveOrAppendResultsKey =>
        columnProfilerRunnerWithRepository = columnProfilerRunnerWithRepository
          .saveOrAppendResult(saveOrAppendResultsKey)
      }

      columnProfilerRunner = columnProfilerRunnerWithRepository
    }

    val profiles = columnProfilerRunner.run()

    val relevantColumns = getRelevantColumns(trainingData.columns(), restrictToColumns)
    val suggestions = applyRules(constraintRules, profiles, relevantColumns)

    (profiles, suggestions)
  }

  private[this] def applyRules(
      constraintRules: Seq[ConstraintRule[ColumnProfile]],
      profiles: ColumnProfiles,
      columns: Seq[String])
    : Seq[ConstraintSuggestion] = {

    columns
      .flatMap { column =>

        val profile = profiles.profiles(column)

        constraintRules
          .filter { _.shouldBeApplied(profile, profiles.numRecords) }
          .map { _.candidate(profile, profiles.numRecords) }
      }
  }

  private[this] def getRelevantColumns(
      columns: Seq[String],
      restrictToColumns: Option[Seq[String]])
    : Seq[String] = {

    columns
      .filter { column => restrictToColumns.isEmpty || restrictToColumns.get.contains(column) }
  }

//  private[this] def saveColumnProfilesJsonToFileSystemIfNecessary(
//      fileOutputOptions: ConstraintSuggestionFileOutputOptions,
//      printStatusUpdates: Boolean,
//      columnProfiles: ColumnProfiles)
//    : Unit = {
//
//    fileOutputOptions.session.foreach { session =>
//      fileOutputOptions.saveColumnProfilesJsonToPath.foreach { profilesOutput =>
//        if (printStatusUpdates) {
//          println(s"### WRITING COLUMN PROFILES TO $profilesOutput")
//        }
//
//        DfsUtils.writeToTextFileOnDfs(session, profilesOutput,
//          overwrite = fileOutputOptions.overwriteResults) { writer =>
//            writer.append(ColumnProfiles.toJson(columnProfiles.profiles.values.toSeq).toString)
//            writer.newLine()
//          }
//        }
//    }
//  }
//
//  private[this] def saveConstraintSuggestionJsonToFileSystemIfNecessary(
//      fileOutputOptions: ConstraintSuggestionFileOutputOptions,
//      printStatusUpdates: Boolean,
//      constraintSuggestions: Seq[ConstraintSuggestion])
//    : Unit = {
//
//    fileOutputOptions.session.foreach { session =>
//      fileOutputOptions.saveConstraintSuggestionsJsonToPath.foreach { constraintsOutput =>
//        if (printStatusUpdates) {
//          println(s"### WRITING CONSTRAINTS TO $constraintsOutput")
//        }
//        DfsUtils.writeToTextFileOnDfs(session, constraintsOutput,
//          overwrite = fileOutputOptions.overwriteResults) { writer =>
//            writer.append(ConstraintSuggestions.toJson(constraintSuggestions).toString)
//            writer.newLine()
//          }
//      }
//    }
//  }
//
//  private[this] def saveEvaluationResultJsonToFileSystemIfNecessary(
//      fileOutputOptions: ConstraintSuggestionFileOutputOptions,
//      printStatusUpdates: Boolean,
//      constraintSuggestions: Seq[ConstraintSuggestion],
//      verificationResult: VerificationResult)
//    : Unit = {
//
//    fileOutputOptions.session.foreach { session =>
//        fileOutputOptions.saveEvaluationResultsJsonToPath.foreach { evaluationsOutput =>
//          if (printStatusUpdates) {
//            println(s"### WRITING EVALUATION RESULTS TO $evaluationsOutput")
//          }
//          DfsUtils.writeToTextFileOnDfs(session, evaluationsOutput,
//            overwrite = fileOutputOptions.overwriteResults) { writer =>
//            writer.append(ConstraintSuggestions
//              .evaluationResultsToJson(constraintSuggestions, verificationResult))
//            writer.newLine()
//          }
//        }
//      }
//  }

  private[this] def evaluateConstraintsIfNecessary(
     testData: Option[Dataset],
     engine: Engine,
     printStatusUpdates: Boolean,
     constraintSuggestions: Seq[ConstraintSuggestion])//,
//     fileOutputOptions: ConstraintSuggestionFileOutputOptions)
    : Option[VerificationResult] = {

    if (testData.isDefined) {
      if (printStatusUpdates) {
        println("### RUNNING EVALUATION")
      }
      val constraints = constraintSuggestions.map { constraintSuggestion =>
        constraintSuggestion.constraint }
      val generatedCheck = Check(CheckLevel.Warning, "generated constraints", constraints)

      val verificationResult = VerificationSuite()
        .onData(testData.get, engine)
        .addCheck(generatedCheck)
        .run()

//      saveEvaluationResultJsonToFileSystemIfNecessary(
//        fileOutputOptions,
//        printStatusUpdates,
//        constraintSuggestions,
//        verificationResult)

      Option(verificationResult)
    } else {
      None
    }
  }

}

object ConstraintSuggestionRunner {

  def apply(): ConstraintSuggestionRunner = {
    new ConstraintSuggestionRunner()
  }
}
