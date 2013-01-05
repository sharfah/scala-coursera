import sbt._
import Keys._

import scalaz.Scalaz.mkIdentity
import scalaz.{Success, Failure}
import com.typesafe.sbteclipse.plugin.EclipsePlugin.EclipseKeys

/**
 * See README.md for high-level overview
 *
 * Libraries Doc Links
 *
 * Coursera API
 *  - http://support.coursera.org/customer/portal/articles/573466-programming-assignments
 *  - the python script 'submit.py' that can be downloaded from the above site
 *
 * SBT
 *  - https://github.com/harrah/xsbt/wiki/Getting-Started-Full-Def
 *  - https://github.com/harrah/xsbt/wiki/Getting-Started-Custom-Settings
 *  - https://github.com/harrah/xsbt/wiki/Getting-Started-More-About-Settings
 *  - https://github.com/harrah/xsbt/wiki/Input-Tasks
 *  - https://github.com/harrah/xsbt/wiki/Tasks
 *  - http://harrah.github.com/xsbt/latest/api/index.html
 *  - https://groups.google.com/forum/?fromgroups#!forum/simple-build-tool
 *
 * Dispatch
 *  - http://dispatch-classic.databinder.net/Response+Bodies.html
 *  - http://www.flotsam.nl/dispatch-periodic-table.html
 *  - http://databinder.net/dispatch-doc/
 *
 * Scalaz
 *  - http://www.lunatech-research.com/archives/2012/03/02/validation-scala
 *  - http://scalaz.github.com/scalaz/scalaz-2.9.1-6.0.4/doc/index.html#scalaz.Validation
 *
 * Apache Commons Codec 1.4
 *  - http://www.jarvana.com/jarvana/view/commons-codec/commons-codec/1.4/commons-codec-1.4-javadoc.jar!/index.html
 *
 * Scalatest
 *  - http://doc.scalatest.org/1.8/index.html#org.scalatest.package
 */
object ProgFunBuild extends Build {

  /***********************************************************
   * MAIN PROJECT DEFINITION
   */

  lazy val assignmentProject = Project(id = "assignment", base = file(".")) settings(
    // 'submit' depends on 'packageSrc', so needs to be a project-level setting: on build-level, 'packageSrc' is not defined
    submitSetting,
    createHandoutSetting,
    // put all libs in the lib_managed directory, that way we can distribute eclipse project files
    retrieveManaged := true,
    EclipseKeys.relativizeLibs := true,
    // Avoid generating eclipse source entries for the java directories
    (unmanagedSourceDirectories in Compile) <<= (scalaSource in Compile)(Seq(_)),
    (unmanagedSourceDirectories in Test) <<= (scalaSource in Test)(Seq(_)),
    commonSourcePackages := Seq(), // see build.sbt
    gradingTestPackages := Seq(),  // see build.sbt
    selectMainSources,
    selectTestSources,
    scalaTestSetting,
    styleCheckSetting,
    setTestPropertiesSetting,
    setTestPropertiesHook
  ) settings (packageSubmissionFiles: _*)


  /***********************************************************
   * SETTINGS AND TASKS
   */

  /** The 'submit' task uses this project name (defined in the build.sbt file) to know where to submit the solution */
  val submitProjectName = SettingKey[String]("submitProjectName")

  /** Project-specific settings, see main build.sbt */
  val projectDetailsMap = SettingKey[Map[String, ProjectDetails]]("projectDetailsMap")

  /**
   * The files that are handed out to students. Accepts a string denoting the project name for
   * which a handout will be generated.
   */
  val handoutFiles = TaskKey[String => PathFinder]("handoutFiles")

  /**
   * This setting allows to restrict the source files that are compiled and tested
   * to one specific project. It should be either the empty string, in which case all
   * projects are included, or one of the project names from the projectDetailsMap.
   */
  val currentProject = SettingKey[String]("currentProject")

  /** Package names of source packages common for all projects, see comment in build.sbt */
  val commonSourcePackages = SettingKey[Seq[String]]("commonSourcePackages")

  /** Package names of test sources for grading, see comment in build.sbt */
  val gradingTestPackages = SettingKey[Seq[String]]("gradingTestPackages")

  /************************************************************
   * SUBMITTING A SOLUTION TO COURSERA
   */

  val packageSubmission = TaskKey[File]("packageSubmission")

  val packageSubmissionFiles = {
    // the packageSrc task uses Defaults.packageSrcMappings, which is defined as concatMappings(resourceMappings, sourceMappings)
    // in the packageSubmisson task we only use the sources, not the resources.
    inConfig(Compile)(Defaults.packageTaskSettings(packageSubmission, Defaults.sourceMappings))
  }

  /** Task to submit a solution to coursera */
  val submit = InputKey[Unit]("submit")

  lazy val submitSetting = submit <<= inputTask { argTask =>
    (argTask, compile in Compile, currentProject, (packageSubmission in Compile), submitProjectName, projectDetailsMap, streams) map { (args, _, currentProject, sourcesJar, projectName, detailsMap, s) =>
      if (currentProject != "") {
        val msg =
          """The 'currentProject' setting is not empty: '%s'
            |
            |This error only appears if there are mistakes in the build scripts. Please re-download the assignment
            |from the coursera webiste. Make sure that you did not perform any changes to the build files in the
            |`project/` directory. If this error persits, ask for help on the course forums.""".format(currentProject).stripMargin +"\n "
        s.log.error(msg)
        failSubmit()
      } else args match {
        case email :: otPassword :: Nil =>
          lazy val wrongNameMsg =
            """Unknown project name: %s
              |
              |This error only appears if there are mistakes in the build scripts. Please re-download the assignment
              |from the coursera webiste. Make sure that you did not perform any changes to the build files in the
              |`project/` directory. If this error persits, ask for help on the course forums.""".format(projectName).stripMargin +"\n "
          // log strips empty lines at the ond of `msg`. to have one we add "\n "
          val details = detailsMap.getOrElse(projectName, {s.log.error(wrongNameMsg); failSubmit()})
          submitSources(sourcesJar, details.assignmentPartId, email, otPassword, s.log)
        case _ =>
          val msg =
            """No e-mail address and / or submission password provided. The required syntax for `submit` is
              |  submit <e-mail> <submissionPassword>
              |
              |The submission password, which is NOT YOUR LOGIN PASSWORD, can be obtained from the assignment page
              |  https://class.coursera.org/%s/assignment/index""".format(Settings.courseId).stripMargin +"\n "
          s.log.error(msg)
          failSubmit()
      }
    }
  }


  def submitSources(sourcesJar: File, partId: String, email: String, otPassword: String, logger: Logger) {
    import CourseraHttp._
    logger.info("Connecting to coursera. Obtaining challenge...")
    val res = for {
      challenge  <- getChallenge(email, partId)
      chResponse <- {
        logger.info("Computing challenge response...")
        challengeResponse(challenge, otPassword).successNel[String]
      }
      response   <- {
        logger.info("Submitting solution...")
        submitSolution(sourcesJar, partId, challenge, chResponse)
      }
    } yield response

    res match {
      case Failure(msgs) =>
        for (msg <- msgs.list) logger.error(msg)
        failSubmit()
      case Success(response) =>
        logger.success("Your code was successfully submitted: "+ response)
    }
  }


  def failSubmit(): Nothing = {
    sys.error("Submission failed")
  }



  /***********************************************************
   * CREATE THE HANDOUT ZIP FILE
   */

  val createHandout = InputKey[File]("createHandout")

  // depends on "compile in Test" to make sure everything compiles. also makes sure that
  // all dependencies are downloaded, because we pack the .jar files into the handout.
  lazy val createHandoutSetting = createHandout <<= inputTask { argTask =>
    (argTask, currentProject, baseDirectory, handoutFiles, submitProjectName, target, projectDetailsMap, compile in Test) map { (args, currentProject, basedir, filesFinder, submitProject, targetDir, detailsMap, _) =>
      if (currentProject != "")
        sys.error("\nthe 'currentProject' setting in build.sbt needs to be \"\" in order to create a handout")
      else args match {
        case handoutProjectName :: eclipseDone :: Nil if eclipseDone == "eclipseWasCalled" =>
          if (handoutProjectName != submitProject)
            sys.error("\nThe `submitProjectName` setting in `build.sbt` must match the project name for which a handout is generated\n ")
          val files = filesFinder(handoutProjectName).get
          val filesWithRelativeNames = files.x_!(relativeTo(basedir)) map {
            case (file, name) => (file, handoutProjectName+"/"+name)
          }
          val targetZip = targetDir / (handoutProjectName +".zip")
          IO.zip(filesWithRelativeNames, targetZip)
          targetZip
        case _ =>
          val msg ="""
            |
            |Failed to create handout. Syntax: `createHandout <projectName> <eclipseWasCalled>`
            |
            |Valid project names are: %s
            |
            |The argument <eclipseWasCalled> needs to be the string "eclipseWasCalled". This is to remind
            |you that you **need** to manually run the `eclipse` command before running `createHandout`.
            | """.stripMargin.format(detailsMap.keys.mkString(", "))
          sys.error(msg)
      }
    }
  }


  /************************************************************
   * LIMITING SOURCES TO CURRENT PROJECT
   */

  def filter(basedir: File, packages: Seq[String]) = new FileFilter {
    def accept(file: File) = {
      basedir.equals(file) || {
        IO.relativize(basedir, file) match {
          case Some(str) =>
            packages exists { pkg =>
              str.startsWith(pkg)
            }
          case _ =>
            sys.error("unexpected test file: "+ file +"\nbase dir: "+ basedir)
        }
      }
    }
  }

  def projectFiles(allFiles: Seq[File], basedir: File, projectName: String, globalPackages: Seq[String], detailsMap: Map[String, ProjectDetails]) = {
    if (projectName == "") allFiles
    else detailsMap.get(projectName) match {
      case Some(project) =>
        val finder = allFiles ** filter(basedir, globalPackages :+ project.packageName)
        finder.get
      case None =>
        sys.error("currentProject is set to an invalid name: "+ projectName)
    }
  }

  /**
   * Only include source files of 'currentProject', helpful when preparign a specific assignment.
   * Also keeps the source packages in 'commonSourcePackages'.
   */
  val selectMainSources = {
    (unmanagedSources in Compile) <<= (unmanagedSources in Compile, scalaSource in Compile, projectDetailsMap, currentProject, commonSourcePackages) map { (sources, srcMainScalaDir, detailsMap, projectName, commonSrcs) =>
      projectFiles(sources, srcMainScalaDir, projectName, commonSrcs, detailsMap)
    }
  }

  /**
   * Only include the test files which are defined in the package of the current project.
   * Also keeps test sources in packages listed in 'gradingTestPackages'.
   */
  val selectTestSources = {
    (unmanagedSources in Test) <<= (unmanagedSources in Test, scalaSource in Test, projectDetailsMap, currentProject, gradingTestPackages) map { (sources, srcTestScalaDir, detailsMap, projectName, gradingSrcs) =>
      projectFiles(sources, srcTestScalaDir, projectName, gradingSrcs, detailsMap)
    }
  }


  /************************************************************
   * PARAMETERS FOR RUNNING THE TESTS
   *
   * Setting some system properties that are parameters for the GradingSuite test
   * suite mixin. This is for running the `test` task in SBT's JVM. When running
   * the `scalaTest` task, the ScalaTestRunner creates a new JVM and passes the
   * same properties.
   */

  val setTestProperties = TaskKey[Unit]("setTestProperties")
  val setTestPropertiesSetting = setTestProperties := {
    import scala.util.Properties._
    import Settings._
    setProp(scalaTestIndividualTestTimeoutProperty, individualTestTimeout.toString)
    setProp(scalaTestDefaultWeigthProperty, scalaTestDefaultWeigth.toString)
  }

  val setTestPropertiesHook = (test in Test) <<= (test in Test).dependsOn(setTestProperties)


  /************************************************************
   * RUNNING WEIGHTED SCALATEST & STYLE CHECKER ON DEVELOPMENT SOURCES
   */

  def copiedResourceFiles(copied: collection.Seq[(java.io.File, java.io.File)]): List[File] = {
    copied collect {
      case (from, to) if to.isFile => to
    } toList
  }

  val scalaTest = TaskKey[Unit]("scalaTest")
  val scalaTestSetting = scalaTest <<=
    (compile in Compile,
      compile in Test,
      fullClasspath in Test,
      copyResources in Compile,
      classDirectory in Test,
      baseDirectory,
      streams) map { (_, _, classpath, resources, testClasses, basedir, s) =>
    // we use `map`, so this is only executed if all dependencies succeed. no need to check `GradingFeedback.isFailed`
      val logger = s.log
      val outfile = basedir / Settings.testResultsFileName
      val policyFile = basedir / Settings.policyFileName
      val (score, maxScore, feedback, runLog) = ScalaTestRunner.runScalaTest(classpath, testClasses, outfile, policyFile, copiedResourceFiles(resources), logger.error(_))
      logger.info(feedback)
      logger.info("Test Score: "+ score +" out of "+ maxScore)
      if (!runLog.isEmpty) {
        logger.info("Console output of ScalaTest process")
        logger.info(runLog)
      }
    }

  val styleCheck = TaskKey[Unit]("styleCheck")

  /**
   * depend on compile to make sure the sources pass the compiler
   */
  val styleCheckSetting = styleCheck <<= (compile in Compile, sources in Compile, streams) map { (_, sourceFiles, s) =>
    val logger = s.log
    val (feedback, score) = StyleChecker.assess(sourceFiles)
    logger.info(feedback)
    logger.info("Style Score: "+ score +" out of "+ StyleChecker.maxResult)
  }


  /************************************************************
   * PROJECT DEFINITION FOR GRADING
   */

  lazy val submissionProject = Project(id = "submission", base = file(Settings.submissionDirName)) settings(
    /** settings we take over from the assignment project */
    version <<= (version in assignmentProject),
    name <<= (name in assignmentProject),
    scalaVersion <<= (scalaVersion in assignmentProject),
    scalacOptions <<= (scalacOptions in assignmentProject),
    libraryDependencies <<= (libraryDependencies in assignmentProject),

    /** settings specific to the grading project */
    initGradingSetting,
    // default value, don't change. see comment on `val partIdOfGradingProject`
    partIdOfGradingProject := "",
    gradeProjectDetailsSetting,
    setMaxScoreSetting,
    setMaxScoreHook,
    // default value, don't change. see comment on `val apiKey`
    apiKey := "",
    getSubmissionSetting,
    getSubmissionHook,
    submissionLoggerSetting,
    readCompileLog,
    readTestCompileLog,
    setTestPropertiesSetting,
    setTestPropertiesHook,
    resourcesFromAssignment,
    selectResourcesForProject,
    testSourcesFromAssignment,
    selectTestsForProject,
    scalaTestSubmissionSetting,
    styleCheckSubmissionSetting,
    gradeSetting,
    EclipseKeys.skipProject := true
  )

  /**
   * The assignment part id of the project to be graded. Don't hard code this setting in .sbt or .scala, this
   * setting should remain a (command-line) parameter of the `submission/grade` task, defined when invoking sbt.
   * See also feedback string in "val gradeProjectDetailsSetting".
   */
  val partIdOfGradingProject = SettingKey[String]("partIdOfGradingProject")

  /**
   * The api key to access non-public api parts on coursera. This key is secret! It's defined in
   * 'submission/settings.sbt', which is not part of the handout.
   *
   * Default value 'apiKey' to make the handout sbt project work
   *  - In the handout, apiKey needs to be defined, otherwise the build doesn't compile
   *  - When correcting, we define 'apiKey' in the 'submission/sectrets.sbt' file
   *  - The value in the .sbt file will take precedence when correcting (settings in .sbt take
   *    precedence over those in .scala)
   */
  val apiKey = SettingKey[String]("apiKey")


  /************************************************************
   * GRADING INITIALIZATION
   */

  val initGrading = TaskKey[Unit]("initGrading")
  lazy val initGradingSetting = initGrading <<= (clean, sourceDirectory, baseDirectory) map { (_, submissionSrcDir, basedir) =>
    deleteFiles(submissionSrcDir, basedir)
    GradingFeedback.initialize()
    RecordingLogger.clear()
  }

  def deleteFiles(submissionSrcDir: File, basedir: File) {
    // don't delete anything in offline mode, useful for us when hacking testing / stylechecking
    if (!Settings.offlineMode){
      IO.delete(submissionSrcDir)
      IO.delete(basedir / Settings.submissionJarFileName)
      IO.delete(basedir / Settings.testResultsFileName)
    }
  }

  /** ProjectDetails of the project that we are grading */
  val gradeProjectDetails = TaskKey[ProjectDetails]("gradeProjectDetails")

  // here we depend on `initialize` because we already use the GradingFeedback
  lazy val gradeProjectDetailsSetting = gradeProjectDetails <<= (initGrading, partIdOfGradingProject, projectDetailsMap in assignmentProject) map { (_, partId, detailsMap) =>
    detailsMap.find(_._2.assignmentPartId == partId) match {
      case Some((_, details)) =>
        details
      case None =>
        val validIds = detailsMap.map(_._2.assignmentPartId)
        val msgRaw =
          """Unknown assignment part id: %s
            |Valid part ids are: %s
            |
            |In order to grade a project, the `partIdOfGradingProject` setting has to be defined. If you are running
            |interactively in the sbt console, type `set (partIdOfGradingProject in submissionProject) := "idString"`.
            |When running the grading task from the command line, add the above `set` command, e.g. execute
            |
            |  sbt 'set (partIdOfGradingProject in submissionProject) := "idString"' submission/grade"""
        val msg = msgRaw.stripMargin.format(partId, validIds.mkString(", ")) + "\n "
        GradingFeedback.downloadUnpackFailed(msg)
        sys.error(msg)
    }
  }

  val setMaxScore = TaskKey[Unit]("setMaxScore")
  val setMaxScoreSetting = setMaxScore <<= (gradeProjectDetails) map { project =>
    GradingFeedback.setMaxScore(project.maxScore, project.styleScoreRatio)
  }

  // set the maximal score before running compile / test / ...
  val setMaxScoreHook = (compile in Compile) <<= (compile in Compile).dependsOn(setMaxScore)


  /************************************************************
   * DOWNLOADING AND EXTRACTING SUBMISSION
   */

  val getSubmission = TaskKey[Unit]("getSubmission")
  val getSubmissionSetting = getSubmission <<= (baseDirectory, scalaSource in Compile) map { (baseDir, scalaSrcDir) =>
    readAndUnpackSubmission(baseDir, scalaSrcDir)
  }

  def readAndUnpackSubmission(baseDir: File, targetSourceDir: File) {
    try {
      val jsonFile = baseDir / Settings.submissionJsonFileName
      val targetJar = baseDir / Settings.submissionJarFileName
      val res = for {
        queueResult <- {
          if (Settings.offlineMode) {
            println("[not unpacking from json file]")
            QueueResult("").successNel
          } else {
            CourseraHttp.readJsonFile(jsonFile, targetJar)
          }
        }
        _ <- {
          GradingFeedback.apiState = queueResult.apiState
          CourseraHttp.unpackJar(targetJar, targetSourceDir)
        }
      } yield ()

      res match {
        case Failure(msgs) =>
          GradingFeedback.downloadUnpackFailed(msgs.list.mkString("\n"))
        case _ =>
          ()
      }
    } catch {
      case e: Throwable =>
        // generate some useful feedback in case something fails
        GradingFeedback.downloadUnpackFailed(CourseraHttp.fullExceptionString(e))
        throw e
    }
    if (GradingFeedback.isFailed) failDownloadUnpack()
  }

  // dependsOn makes sure that `getSubmission` is executed *before* `unmanagedSources`
  val getSubmissionHook = (unmanagedSources in Compile) <<= (unmanagedSources in Compile).dependsOn(getSubmission)

  def failDownloadUnpack(): Nothing = {
    sys.error("Download or Unpack failed")
  }

  /************************************************************
   * READING COMPILATION AND TEST COMPILATION LOGS
   */


  // extraLoggers need to be defined globally. (extraLoggers in Compile) does not work - sbt only
  // looks at the global extraLoggers when creating the LogManager.
  val submissionLoggerSetting = extraLoggers ~= { currentFunction =>
    (key: ScopedKey[_]) => {
      new FullLogger(RecordingLogger) +: currentFunction(key)
    }
  }

  val readCompileLog = (compile in Compile) <<= (compile in Compile) mapR handleFailure(compileFailed)
  val readTestCompileLog = (compile in Test) <<= (compile in Test) mapR handleFailure(compileTestFailed)

  def handleFailure[R](handler: (Incomplete, String) => Unit) = (res: Result[R]) => res match {
    case Inc(inc) =>
      // Only call the handler of the task that actually failed. See comment in GradingFeedback.failed
      if (!GradingFeedback.isFailed)
        handler(inc, RecordingLogger.readAndClear())
      throw inc
    case Value(v) => v
  }

  def compileFailed(inc: Incomplete, log: String) {
    GradingFeedback.compileFailed(log)
  }

  def compileTestFailed(inc: Incomplete, log: String) {
    GradingFeedback.testCompileFailed(log)
  }


  /************************************************************
   * RUNNING SCALATEST
   */

  /** The submission project takes resource files from the main (assignment) project */
  val resourcesFromAssignment = {
    (resourceDirectory in Compile) <<= (resourceDirectory in (assignmentProject, Compile))
  }

  /**
   * Only include the resource files which are defined in the package of the current project.
   */
  val selectResourcesForProject = {
    (resources in Compile) <<= (resources in Compile, resourceDirectory in (assignmentProject, Compile), gradeProjectDetails) map { (resources, resourceDir, project) =>
      val finder = resources ** filter(resourceDir, List(project.packageName))
      finder.get
    }
  }

  /** The submission project takes test files from the main (assignment) project */
  val testSourcesFromAssignment = {
    (sourceDirectory in Test) <<= (sourceDirectory in (assignmentProject, Test))
  }

  /**
   * Only include the test files which are defined in the package of the current project.
   * Also keeps test sources in packages listed in 'gradingTestPackages'
   */
  val selectTestsForProject = {
    (unmanagedSources in Test) <<= (unmanagedSources in Test, scalaSource in (assignmentProject, Test), gradingTestPackages in assignmentProject, gradeProjectDetails) map { (sources, testSrcScalaDir, gradingSrcs, project) =>
      val finder = sources ** filter(testSrcScalaDir, gradingSrcs :+ project.packageName)
      finder.get
    }
  }

  val scalaTestSubmission = TaskKey[Unit]("scalaTestSubmission")
  val scalaTestSubmissionSetting = scalaTestSubmission <<=
    (compile in Compile,
     compile in Test,
     fullClasspath in Test,
     copyResources in Compile,
     classDirectory in Test,
     baseDirectory) map { (_, _, classpath, resources, testClasses, basedir) =>
      // we use `map`, so this is only executed if all dependencies succeed. no need to check `GradingFeedback.isFailed`
      val outfile = basedir / Settings.testResultsFileName
      val policyFile = basedir / ".." / Settings.policyFileName
      ScalaTestRunner.scalaTestGrade(classpath, testClasses, outfile, policyFile, copiedResourceFiles(resources))
  }



  /************************************************************
   * STYLE CHECKING
   */

  val styleCheckSubmission = TaskKey[Unit]("styleCheckSubmission")

  /**
   * - depend on scalaTestSubmission so that test get executed before style checking. the transitive
   *   dependencies also ensures that the "sources in Compile" don't have compilation errors
   * - using `map` makes this task execute only if all its dependencies succeeded.
   */
  val styleCheckSubmissionSetting = styleCheckSubmission <<= (sources in Compile, scalaTestSubmission) map { (sourceFiles, _) =>
    val (feedback, score) = StyleChecker.assess(sourceFiles)
    if (score == StyleChecker.maxResult) {
      GradingFeedback.perfectStyle()
    } else {
      val gradeScore = GradingFeedback.maxStyleScore * score / StyleChecker.maxResult
      GradingFeedback.styleProblems(feedback, gradeScore)
    }
  }



  /************************************************************
   * SUBMITTING GRADES TO COURSERA
   */

  val grade = TaskKey[Unit]("grade")

  // mapR: submit the grade / feedback in any case, also on failure
  val gradeSetting = grade <<= (scalaTestSubmission, styleCheckSubmission, apiKey, streams) mapR { (_, _, apiKeyR, s) =>
    val logOpt = s match {
      case Value(v) => Some(v.log)
      case _ => None
    }
    logOpt.foreach(_.info(GradingFeedback.feedbackString(html = false)))
    apiKeyR match {
      case Value(apiKey) if (!apiKey.isEmpty) =>
        // if build failed early, we did not even get the api key from the submission queue
        if (!GradingFeedback.apiState.isEmpty && !Settings.offlineMode) {
          val scoreString = "%.2f".format(GradingFeedback.totalScore)
          CourseraHttp.submitGrade(GradingFeedback.feedbackString(), scoreString, GradingFeedback.apiState, apiKey) match {
            case Failure(msgs) =>
              sys.error(msgs.list.mkString("\n"))
            case _ =>
              ()
          }
        } else if(Settings.offlineMode) {
          logOpt.foreach(_.info(" \nSettings.offlineMode enabled, not uploading the feedback"))
        } else {
          sys.error("Could not submit feedback - apiState not initialized")
        }
      case _ =>
        sys.error("Could not submit feedback - apiKey not defined: "+ apiKeyR)
    }
  }
}

case class ProjectDetails(packageName: String,
                          assignmentPartId: String,
                          maxScore: Double,
                          styleScoreRatio: Double)
