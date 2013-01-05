import dispatch.{Request, Http, NoLogging, StatusCode, url}
import cc.spray.json.{JsNull, JsonParser, DefaultJsonProtocol, JsValue}
import RichJsValue._
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.codec.binary.{Hex, Base64}
import java.io.{IOException, File, FileInputStream}
import scalaz.Scalaz.{mkIdentity, ValidationNEL}

import Settings._

case class JsonSubmission(api_state: String, user_info: JsValue, submission_metadata: JsValue, solutions: JsValue, submission_encoding: String, submission: String)
//case class JsonQueueResult(submission: JsonSubmission)
object SubmitJsonProtocol extends DefaultJsonProtocol {
  implicit val jsonSubmissionFormat = jsonFormat6(JsonSubmission)
//  implicit val jsonQueueResultFormat = jsonFormat1(JsonQueueResult)
}

object CourseraHttp {
  private lazy val http = new Http with NoLogging

  private def executeRequest[T](req: Request)(parse: String => ValidationNEL[String, T]): ValidationNEL[String, T] = {
    try {
      http(req >- { res =>
        parse(res)
      })
    } catch {
      case ex: IOException =>
        ("Connection failed\n"+ ex.toString()).failNel
      case StatusCode(code, message) =>
        ("HTTP failed with status "+ code +"\n"+ message).failNel
    }
  }


  /******************************
   * SUBMITTING
   */

  def getChallenge(email: String, assignmentPartId: String): ValidationNEL[String, Challenge] = {
    val baseReq = url(challengeUrl)
    val withArgs = baseReq << Map("email_address" -> email,
                                  "assignment_part_sid" -> assignmentPartId,
                                  "response_encoding" -> "delim")

    executeRequest(withArgs) { res =>
      // example result. there might be an `aux_data` value at the end.
      // |email_address|a@b.com|challenge_key|XXYYXXYYXXYY|state|XXYYXXYYXXYY|challenge_aux_data|
      val parts = res.split('|').filterNot(_.isEmpty)
      if (parts.length < 7)
        ("Unexpected challenge format: \n"+ res).failNel
      else
        Challenge(parts(1), parts(3), parts(5)).successNel
    }
  }

  def submitSolution(sourcesJar: File, assignmentPartId: String, challenge: Challenge, chResponse: String): ValidationNEL[String, String] = {
    val fileLength = sourcesJar.length()
    if (!sourcesJar.exists()) {
      ("Sources jar archive does not exist\n"+ sourcesJar.getAbsolutePath).failNel
    } else if (fileLength == 0l) {
      ("Sources jar archive is empty\n"+ sourcesJar.getAbsolutePath).failNel
    } else if (fileLength > maxSubmitFileSize) {
      ("Sources jar archive is too big. Allowed size: "+
        maxSubmitFileSize +" bytes, found "+ fileLength +" bytes.\n"+
        sourcesJar.getAbsolutePath).failNel
    } else {
      val bytes = new Array[Byte](fileLength.toInt)
      val sizeRead = try {
        val is = new FileInputStream(sourcesJar)
        val read = is.read(bytes)
        is.close()
        read
      } catch {
        case ex: IOException =>
          ("Failed to read sources jar archive\n"+ ex.toString()).failNel
      }
      if (sizeRead != bytes.length) {
        ("Failed to read the sources jar archive, size read: "+ sizeRead).failNel
      } else {
        val fileData = encodeBase64(bytes)
        val baseReq = url(submitUrl)
        val withArgs = baseReq << Map("assignment_part_sid" -> assignmentPartId,
                                      "email_address" -> challenge.email,
                                      "submission" -> fileData,
                                      "submission_aux" -> "",
                                      "challenge_response" -> chResponse,
                                      "state" -> challenge.state)
        executeRequest(withArgs) { res =>
          // the API returns HTTP 200 even if there are failures, how impolite...
          if (res.contains("Your submission has been accepted"))
            res.successNel
          else
            res.failNel
        }
      }
    }
  }

  def challengeResponse(challenge: Challenge, otPassword: String): String =
    shaHexDigest(challenge.challengeKey + otPassword)


  /********************************
   * DOWNLOADING SUBMISSIONS
   */

  // def downloadFromQueue(queue: String, targetJar: File, apiKey: String): ValidationNEL[String, QueueResult] = {
  //   val baseReq = url(Settings.submitQueueUrl)
  //   val withArgsAndHeader = baseReq << Map("queue" -> queue) <:< Map("X-api-key" -> apiKey)

  //   executeRequest(withArgsAndHeader) { res =>
  //     extractJson(res, targetJar)
  //   }
  // }

  def readJsonFile(jsonFile: File, targetJar: File): ValidationNEL[String, QueueResult] = {
    extractJson(sbt.IO.read(jsonFile), targetJar)
  }

  def extractJson(jsonData: String, targetJar: File): ValidationNEL[String, QueueResult] = {
    import SubmitJsonProtocol._
    for {
      jsonSubmission <- {
        try {
          val parsed = JsonParser(jsonData)
          val submission = parsed \ "submission"
          if (submission == JsNull) {
            ("Nothing to grade, queue is empty.").failNel
          } else {
            submission.convertTo[JsonSubmission].successNel
          }
        } catch {
          case e: Exception =>
            ("Could not parse submission\n"+ jsonData +"\n"+ fullExceptionString(e)).failNel
        }
      }
      queueResult <- {
        val encodedFile = jsonSubmission.submission
        val jarContent = decodeBase64(encodedFile)
        try {
          sbt.IO.write(targetJar, jarContent)
          QueueResult(jsonSubmission.api_state).successNel
        } catch {
          case e: IOException =>
            ("Failed to write jar file to "+ targetJar.getAbsolutePath +"\n"+ e.toString).failNel
        }
      }
    } yield queueResult
  }

  def unpackJar(file: File, targetDirectory: File): ValidationNEL[String, Unit] = {
    try {
      val files = sbt.IO.unzip(file, targetDirectory)
      if (files.isEmpty)
        ("No files found when unpacking jar file "+ file.getAbsolutePath).failNel
      else
        ().successNel
    } catch {
      case e: IOException =>
        val msg = "Error while unpacking the jar file "+ file.getAbsolutePath +" to "+ targetDirectory.getAbsolutePath +"\n"+ e.toString
        if (Settings.offlineMode) {
          println("[offline mode] "+ msg)
          ().successNel
        } else {
          msg.failNel
        }
    }
  }


  /********************************
   * SUBMITTING GRADES
   */

  def submitGrade(feedback: String, score: String, apiState: String, apiKey: String): ValidationNEL[String, Unit] = {
    import DefaultJsonProtocol._
    val baseReq = url(Settings.uploadFeedbackUrl)
    val withArgs = baseReq << Map("api_state" -> apiState, "score" -> score, "feedback" -> feedback) <:< Map("X-api-key" -> apiKey)
    executeRequest(withArgs) { res =>
      try {
        val js = JsonParser(res)
        val status = (js \ "status").convertTo[String]
        if (status == "202")
          ().successNel
        else
          ("Unexpected result from submit request: "+ status).failNel
      } catch {
        case e: Exception =>
          ("Failed to parse response while submitting grade\n"+ res +"\n"+ fullExceptionString(e)).failNel
      }
    }
  }


  /*********************************
   * TOOLS AND STUFF
   */

  def shaHexDigest(s: String): String = {
    val chars = Hex.encodeHex(DigestUtils.sha(s))
    new String(chars)
  }


  def fullExceptionString(e: Throwable) =
    e.toString +"\n"+ e.getStackTrace.map(_.toString).mkString("\n")


  /* Base 64 tools */

  def encodeBase64(bytes: Array[Byte]): String =
    new String(Base64.encodeBase64(bytes))

  def decodeBase64(str: String): Array[Byte] = {
    // codecs 1.4 has a version accepting a string, but not 1.2; jar hell.
    Base64.decodeBase64(str.getBytes)
  }
}

case class Challenge(email: String, challengeKey: String, state: String)

case class QueueResult(apiState: String)

