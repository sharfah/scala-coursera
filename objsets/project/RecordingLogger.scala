import sbt._
import collection.mutable.ListBuffer

/**
 * Logger to capture compiler output, test output
 */

object RecordingLogger extends Logger {
  private val buffer = ListBuffer[String]()

  def hasErrors = buffer.nonEmpty

  def readAndClear() = {
    val res = buffer.mkString("\n")
    buffer.clear()
    res
  }

  def clear() {
    buffer.clear()
  }

  def log(level: Level.Value, message: => String) =
    if (level == Level.Error) {
      buffer += message
    }

  // we don't log success here
  def success(message: => String) = ()

  // invoked when a task throws an exception. invoked late, when the exception is logged, i.e.
  // just before returning to the prompt. therefore we do nothing: storing the exception in the
  // buffer would happen *after* the `handleFailure` reads the buffer.
  def trace(t: => Throwable) = ()
}
