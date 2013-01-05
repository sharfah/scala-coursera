import collection.mutable.ListBuffer
import org.apache.commons.lang3.StringEscapeUtils

object GradingFeedback {

  private val feedbackSummary = new ListBuffer[String]()
  private val feedbackDetails = new ListBuffer[String]()

  private def addSummary(msg: String) { feedbackSummary += msg; feedbackSummary += "\n\n" }
  private def addDetails(msg: String) { feedbackDetails += msg; feedbackDetails += "\n\n" }

  /**
   * Converts the string to HTML - coursera displays the feedback in an html page.
   */
  def feedbackString(html: Boolean = true) = {
    val total = totalGradeMessage(totalScore) + "\n\n"
    // trim removes the newlines at the end
    val s = (total + feedbackSummary.mkString + feedbackDetails.mkString).trim
    if (html)
      "<pre>"+ StringEscapeUtils.escapeHtml4(s) +"</pre>"
    else
      s
  }

  private var vTestScore: Double = 0d
  private var   vStyleScore: Double = 0d
  def totalScore = vTestScore + vStyleScore

  private var vMaxTestScore: Double = 0d
  private var vMaxStyleScore: Double = 0d
  def maxTestScore = vMaxTestScore
  def maxStyleScore = vMaxStyleScore

  // a string obtained from coursera when downloading an assignment. it has to be
  // used again when uploading the grade.
  var apiState: String = ""

  /**
   * `failed` means that there was an unexpected error during grading. This includes
   *  - student's code does not compile
   *  - our tests don't compile (against the student's code)
   *  - crash while executing ScalaTest (not test failures, but problems trying to run the tests!)
   *  - crash while executing the style checker (again, not finding style problems!)
   *
   * When failed is `true`, later grading stages will not be executed: this is handled automatically
   * by SBT, tasks depending on a failed one are not run.
   *
   * However, these dependent tasks still fail (i.e. mapR on them is invoked). The variable below
   * allows us to know if something failed before. In this case, we don't add any more things to
   * the log. (see `ProgFunBuild.handleFailure`)
   */
  private var failed = false
  def isFailed = failed

  def initialize() {
    feedbackSummary.clear()
    feedbackDetails.clear()
    vTestScore = 0d
    vStyleScore = 0d
    apiState = ""
    failed = false
  }

  def setMaxScore(maxScore: Double, styleScoreRatio: Double) {
    vMaxTestScore = maxScore * (1-styleScoreRatio)
    vMaxStyleScore = maxScore * styleScoreRatio
  }


  /* Methods to build up the feedback log */

  def downloadUnpackFailed(log: String) {
    failed = true
    addSummary(downloadUnpackFailedMessage)
    addDetails("======== FAILURES WHILE DOWNLOADING OR EXTRACTING THE SUBMISSION ========")
    addDetails(log)
  }


  def compileFailed(log: String) {
    failed = true
    addSummary(compileFailedMessage)
    addDetails("======== COMPILATION FAILURES ========")
    addDetails(log)
  }

  def testCompileFailed(log: String) {
    failed = true
    addSummary(testCompileFailedMessage)
    addDetails("======== TEST COMPILATION FAILURES ========")
    addDetails(log)
  }



  def allTestsPassed() {
    addSummary(allTestsPassedMessage)
    vTestScore = maxTestScore
  }

  def testsFailed(log: String, score: Double) {
    addSummary(testsFailedMessage(score))
    vTestScore = score
    addDetails("======== LOG OF FAILED TESTS ========")
    addDetails(log)
  }

  def testExecutionFailed(log: String) {
    failed = true
    addSummary(testExecutionFailedMessage)
    addDetails("======== ERROR LOG OF TESTING TOOL ========")
    addDetails(log)
  }

  def testExecutionDebugLog(log: String) {
    addDetails("======== DEBUG OUTPUT OF TESTING TOOL ========")
    addDetails(log)
  }



  def perfectStyle() {
    addSummary(perfectStyleMessage)
    vStyleScore = maxStyleScore
  }

  def styleProblems(log: String, score: Double) {
    addSummary(styleProblemsMessage(score))
    vStyleScore = score
    addDetails("======== CODING STYLE ISSUES ========")
    addDetails(log)
  }



  /* Feedback Messages */

  private val downloadUnpackFailedMessage =
    """We were not able to download your submission from the coursera servers, or extracting the
      |archive containing your source code failed.
      |
      |If you see this error message as your grade feedback, please contact one of the teaching
      |assistants. See below for a detailed error log.""".stripMargin

  private val compileFailedMessage =
    """We were not able to compile the source code you submitted. This is not expected to happen,
      |because the `submit` command in SBT can only be executed if your source code compiles.
      |
      |Please verify the following points:
      | - You should use the `submit` command in SBT to upload your solution
      | - You should not perform any changes to the SBT project definition files, i.e. the *.sbt
      |   files, and the files in the `project/` directory
      |
      |Take a careful look at the compiler output below - maybe you can find out what the problem is.
      |
      |If you cannot find a solution, ask for help on the discussion forums on the course website:
      |  %s""".stripMargin.format(Settings.forumUrl)


  private val testCompileFailedMessage =
    """We were not able to compile our tests, and therefore we could not correct your submission.
      |
      |The most likely reason for this problem is that your submitted code uses different names
      |for methods, classes, objects or different types than expected.
      |
      |In principle, this can only arise if you changed some names or types in the code that we
      |provide, for instance a method name or a parameter type.
      |
      |To diagnose your problem, perform the following steps:
      | - Run the tests that we provide with our hand-out. These tests verify that all names and
      |   types are correct. In case these tests pass, but you still see this message, please post
      |   a report on the forums [1].
      | - Take a careful look at the error messages from the Scala compiler below. They should give
      |   you a hint where your code has an unexpected shape.
      |
      |If you cannot find a solution, ask for help on the discussion forums [1] on the course website.
      |
      |[1] Course forum: %s""".stripMargin.format(Settings.forumUrl)


  private def testsFailedMessage(score: Double) =
    """The code you submitted did not pass all of our tests: your submission achieved a score of
      |%.2f out of %.2f in our tests.
      |
      |In order to find bugs in your code, we advise to perform the following steps:
      | - Take a close look at the test output that you can find below: it should point you to
      |   the part of your code that has bugs.
      | - Run the tests that we provide with the handout on your code.
      | - The tests we provide do not test your code in depth: they are very incomplete. In order
      |   to test more aspects of your code, write your own unit tests.
      | - Take another very careful look at the assignment description. Try to find out if you
      |   misunderstood parts of it. While reading through the assignment, write more tests.
      |
      |Below you can find a short feedback for every individual test that failed.""".stripMargin.format(score, vMaxTestScore)

  // def so that we read the right value of vMaxTestScore (initialize modifies it)
  private def allTestsPassedMessage =
    """Your solution passed all of our tests, congratulations! You obtained the maximal test
      |score of %.2f.""".stripMargin.format(vMaxTestScore)

  private val testExecutionFailedMessage =
    """An error occured while running our tests on your submission. This is not expected to
      |happen, it means there is a bug in our testing environment.
      |
      |In order for us to help you, please contact one of the teaching assistants and send
      |them the entire feedback message that you recieved.""".stripMargin

  // def so that we read the right value of vMaxStyleScore (initialize modifies it)
  private def perfectStyleMessage =
    """Our automated style checker tool could not find any issues with your code. You obtained the maximal
      |style score of %.2f.""".stripMargin.format(vMaxStyleScore)


  private def styleProblemsMessage(score: Double) =
    """Our automated style checker tool found issues in your code with respect to coding style: it
      |computed a style score of %.2f out of %.2f for your submission. See below for detailed feedback.""".stripMargin.format(score, vMaxStyleScore)


  private def totalGradeMessage(score: Double) =
    """Your overall score for this assignment is %.2f out of %.2f""".format(score, vMaxTestScore + vMaxStyleScore)
}
