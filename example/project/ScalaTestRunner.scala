import sbt._
import Keys._
import sys.process.{Process => SysProc, ProcessLogger}
import java.util.concurrent._
import collection.mutable.ListBuffer

object ScalaTestRunner {

  private def forkProcess(proc: SysProc, timeout: Int) {
    val executor = Executors.newSingleThreadExecutor()
    val future: Future[Unit] = executor.submit(new Callable[Unit] {
      def call { proc.exitValue() }
    })
    try {
      future.get(timeout, TimeUnit.SECONDS)
    } catch {
      case to: TimeoutException =>
        future.cancel(true)
        throw to
    } finally {
      executor.shutdown()
    }
  }

  private def runPathString(file: File) = file.getAbsolutePath().replace(" ", "\\ ")

  private def extractWeights(s: String, logError: String => Unit) = {
    try {
      val (nums, rest) = s.span(c => c != '\n')
      val List(grade, max) = nums.split(';').toList
      (grade.toInt, max.toInt, rest.drop(1))
    } catch {
      case e: Throwable =>
        val msg = "Could not extract weight from grading feedback\n"+ s
        logError(msg)
        throw e
    }
  }


  def runScalaTest(classpath: Classpath, testClasses: File, outfile: File, policyFile: File, logError: String => Unit) = {
    val classpathString = classpath map {
      case Attributed(file) => file.getAbsolutePath()
    } mkString(":")

    val testRunpath = runPathString(testClasses)

    val outfileStr = outfile.getAbsolutePath
    val policyFileStr = policyFile.getAbsolutePath
    // Deleting the file is helpful: it makes reading the file below crash in case ScalaTest doesn't
    // run as expected. Problem is, it's hard to detect if ScalaTest ran successfully or not: it
    // exits with non-zero if there are failed tests, and also if it crashes...
    new java.io.File(outfileStr).delete()

    // we don't specify "-w packageToTest" - the build file only compiles the tests
    // for the current project. so we don't need to do it again here.
    val cmd = "java" ::
      "-Djava.security.manager" :: "-Djava.security.policy="+ policyFileStr ::
      "-DscalaTestReportFile="+ outfileStr ::
      "-cp" :: classpathString ::
      "org.scalatest.tools.Runner" ::
      "-R" :: testRunpath ::
      "-C" :: "grading.CourseraReporter" ::
      Nil

    // process deadlocks in Runner.PassFailReporter.allTestsPassed on runDoneSemaphore.acquire() when
    // something is wrong, e.g. when there's an error.. So we have to run it with a timeout.

    val out = new ListBuffer[String]()
    var p: SysProc = null
    try {
      p = SysProc(cmd).run(ProcessLogger(out.append(_), out.append(_)))
      forkProcess(p, Settings.scalaTestTimeout)
    } catch {
      case e: TimeoutException =>
        val msg = "Timeout when running ScalaTest"+ out.mkString("\n","\n", "")
        logError(msg)
        p.destroy()
        sys.error(msg)

      case e: Throwable =>
        val msg = "Error occured while running the ScalaTest command\n"+ e.toString + out.mkString("\n","\n", "")
        logError(msg)
        p.destroy()
        throw e
    }


    val feedbackFileContent = try {
      io.Source.fromFile(outfileStr).mkString
    } catch {
      case e: Throwable =>
        val msg = "Error occured while reading the output file of ScalaTest\n"+ e.toString + out.mkString("\n","\n", "")
        logError(msg)
        throw e
    }

    val (score, maxScore, feedback) = extractWeights(feedbackFileContent, logError)
    val runLog = out.mkString("\n").trim
    (score, maxScore, feedback, runLog)
  }

  def scalaTestGrade(classpath: Classpath, testClasses: File, outfile: File, policyFile: File) {
    val (score, maxScore, feedback, runLog) = runScalaTest(classpath, testClasses, outfile, policyFile, GradingFeedback.testExecutionFailed)
      if (score == maxScore) {
      GradingFeedback.allTestsPassed()
    } else {
      val scaledScore = GradingFeedback.maxTestScore * score / maxScore
      GradingFeedback.testsFailed(feedback, scaledScore)
    }

    // The output `out` should in principle be empty: the reporter we use writes its results to a file.
    // however, `out` contains valuable logs in case scalatest fails. We need to put them into the student
    // feedback in order to have a chance of debugging problems.

    if (!runLog.isEmpty) {
      GradingFeedback.testExecutionDebugLog(runLog)
    }
  }
}

