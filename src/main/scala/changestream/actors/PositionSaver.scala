package changestream.actors

import java.io._
import java.nio.charset.StandardCharsets

import scala.concurrent.duration._
import scala.language.postfixOps
import akka.actor.{Actor, ActorRef, Cancellable}
import changestream.events.MutationWithInfo
import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.LoggerFactory

import scala.io.Source

object PositionSaver {
  // TODO: position should be a struct
  // TODO: gtid support
  case class SavePositionRequest(positionOverride: Option[String])
  case object GetPositionRequest
  case object GetLastSavedPositionRequest
  case class GetPositionResponse(position: Option[String])
  case class EmitterResult(position: String, meta: Option[Any] = None)
}

class PositionSaver(config: Config = ConfigFactory.load().getConfig("changestream")) extends Actor {
  import PositionSaver._

  protected val log = LoggerFactory.getLogger(getClass)
  protected implicit val ec = context.dispatcher

  protected val MAX_RECORDS = config.getLong("position-saver.max-records")
  protected val MAX_WAIT = config.getLong("position-saver.max-wait").milliseconds

  protected val SAVER_FILE_PATH = config.getString("position-saver.file-path")
  protected lazy val saverFile = new File(SAVER_FILE_PATH)

  protected var cancellableSchedule: Option[Cancellable] = None
  protected def setDelayedSave(origSender: ActorRef) = {
    val scheduler = context.system.scheduler
    cancellableSchedule = MAX_WAIT.length match {
      case 0 => None
      case _ => Some(scheduler.scheduleOnce(MAX_WAIT) { self ! SavePositionRequest })
    }
  }
  protected def cancelDelayedSave = cancellableSchedule.foreach(_.cancel())

  // Mutable State!!
  protected var currentRecordCount = 0
  protected var currentPosition: Option[String] = None
  // End Mutable State!!

  private def readPosition: Option[String] = {
    if(saverFile.exists()) {
      try {
        val bufferedSource = Source.fromFile(saverFile, "UTF-8")
        val position = bufferedSource.getLines.mkString match {
          case "" => None
          case str:String => Some(str)
        }
        bufferedSource.close
        position
      } catch {
        case exception: IOException =>
          log.error(s"Failed to read position from position file (${SAVER_FILE_PATH}): ${exception.getMessage}")
          throw exception
      }
    }
    else {
      None
    }
  }

  def writePosition(position: Option[String], sender: Option[ActorRef] = None) = {
    try {
      val saverOutputStream = new FileOutputStream(saverFile)
      val saverWriter = new OutputStreamWriter(saverOutputStream, StandardCharsets.UTF_8)
      saverWriter.write(position match {
        case None => "" //TODO none and empty string mean the same thing right now. Is this cool?
        case Some(str) => str
      })
      saverWriter.close()
      sender.map(_ ! akka.actor.Status.Success(position))
    } catch {
      case exception: IOException =>
        log.error(s"Failed to write position to position file (${SAVER_FILE_PATH}): ${exception.getMessage}")
        sender.map(_ ! akka.actor.Status.Failure(exception))
        throw exception
    }
  }

  override def preStart() = {
    currentPosition = readPosition
    writePosition(currentPosition) //make sure we can write to the file (write back position)
    log.info(s"Ready to save positions to file ${SAVER_FILE_PATH}.")
  }

  override def postStop() = cancelDelayedSave

  def receive = {
    case EmitterResult(position, meta) =>
      log.debug(s"Received position: ${position}")

      cancelDelayedSave
      currentRecordCount += 1
      currentPosition = Some(position)

      currentRecordCount match {
        case MAX_RECORDS =>
          currentRecordCount = 0
          writePosition(currentPosition, Some(sender()))
        case _ =>
          setDelayedSave(sender())
      }

    case SavePositionRequest(Some(overridePosition: String)) =>
      currentPosition = Some(overridePosition)
      writePosition(currentPosition, Some(sender()))

    case SavePositionRequest =>
      writePosition(currentPosition, Some(sender()))

    case GetPositionRequest =>
      sender() ! GetPositionResponse(currentPosition)

    case GetLastSavedPositionRequest =>
      sender() ! GetPositionResponse(readPosition)
  }
}
