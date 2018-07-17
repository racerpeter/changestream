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

// TODO Specs
object PositionSaver {
  case object SavePositionRequest
  case class GetPositionRequest(origSender: ActorRef)
  case class GetPositionResponse(position: String)
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
    cancellableSchedule = Some(scheduler.scheduleOnce(MAX_WAIT) { self ! SavePositionRequest })
  }
  protected def cancelDelayedSave = cancellableSchedule.foreach(_.cancel())

  // Mutable State!!
  protected var currentRecordCount = 0
  protected var currentPosition = ""
  // End Mutable State!!

  def writePosition(position: String) = {
    try {
      val saverOutputStream = new FileOutputStream(saverFile)
      val saverWriter = new OutputStreamWriter(saverOutputStream, StandardCharsets.UTF_8)
      saverWriter.write(position)
      saverWriter.close()
    } catch {
      case exception: IOException =>
        log.error(s"Failed to write position to position file (${SAVER_FILE_PATH}): ${exception.getMessage}")
        throw exception
    }
  }

  override def preStart() = {
    if(saverFile.exists()) {
      try {
        val bufferedSource = Source.fromFile(saverFile, "UTF-8")
        currentPosition = bufferedSource.getLines.mkString
        bufferedSource.close
      } catch {
        case exception: IOException =>
          log.error(s"Failed to read position from position file (${SAVER_FILE_PATH}): ${exception.getMessage}")
          throw exception
      }
    }

    writePosition(currentPosition) //make sure we can write to the file (write back position)
    log.debug(s"Ready to save positions to file ${SAVER_FILE_PATH}.")
  }

  override def postStop() = cancelDelayedSave

  def receive = {
    case MutationWithInfo(mutation, _, _, Some(_: String)) =>
      log.debug(s"Received message with sequence number: ${mutation.sequence}")

      cancelDelayedSave
      currentRecordCount += 1
      currentPosition = ""

      currentRecordCount match {
        case MAX_RECORDS => self ! SavePositionRequest
        case _ => setDelayedSave(sender())
      }

    case SavePositionRequest =>
      writePosition(currentPosition)

    case GetPositionRequest(requester: ActorRef) =>
      requester ! GetPositionResponse(currentPosition)
  }
}
