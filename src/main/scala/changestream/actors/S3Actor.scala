package changestream.actors

import java.io.ByteArrayInputStream

import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}
import akka.actor.{Actor, ActorRef, Cancellable}
import changestream.events.MutationWithInfo
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.model.{ObjectMetadata, PutObjectRequest, PutObjectResult}
import com.github.dwhjames.awswrap.s3.AmazonS3ScalaClient
import com.typesafe.config.{Config, ConfigFactory}
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import collection.JavaConverters._
import scala.concurrent.{Await, Future}

object S3Actor {
  case class FlushRequest(origSender: ActorRef)
}

class S3Actor(config: Config = ConfigFactory.load().getConfig("changestream")) extends Actor {
  import S3Actor.FlushRequest

  protected val log = LoggerFactory.getLogger(getClass)
  protected implicit val ec = context.dispatcher

  protected val BUCKET = config.getString("aws.s3.bucket")
  protected val KEY_PREFIX = config.getString("aws.s3.key-prefix") match {
    case s if s.endsWith("/") => s
    case s => s"$s/"
  }
  protected val BATCH_SIZE = config.getLong("aws.s3.batch-size")
  protected val MAX_WAIT = config.getLong("aws.s3.flush-timeout").milliseconds
  protected val TIMEOUT = config.getInt("aws.timeout")

  protected var cancellableSchedule: Option[Cancellable] = None
  protected def setDelayedFlush(origSender: ActorRef) = {
    val scheduler = context.system.scheduler
    cancellableSchedule = Some(scheduler.scheduleOnce(MAX_WAIT) { self ! FlushRequest(origSender) })
  }
  protected def cancelDelayedFlush = cancellableSchedule.foreach(_.cancel())

  protected val messageBuffer = mutable.ArrayBuffer.empty[String]

  protected def getMetadata(length: Int, sseAlgorithm: String = "AES256") = {
    val metadata = new ObjectMetadata()
    metadata.setSSEAlgorithm(sseAlgorithm)
    metadata.setContentLength(length)
    metadata
  }

  protected def getMessageBatch: String = {
    val messages = messageBuffer.reduceLeft(_ + "\n" + _)
    messageBuffer.clear()

    messages
  }

  protected def putString(key: String, contents: String): Future[PutObjectResult] = {
    val inputBytes = contents.getBytes("UTF-8")
    val inputStream = new ByteArrayInputStream(inputBytes)
    val metadata = getMetadata(inputBytes.length)
    client.putObject(new PutObjectRequest(BUCKET, s"${KEY_PREFIX}${key}", inputStream, metadata))
  }

  protected val client = new AmazonS3ScalaClient(
    new DefaultAWSCredentialsProviderChain(),
    new ClientConfiguration().
      withConnectionTimeout(TIMEOUT),
    Regions.fromName(config.getString("aws.region"))
  )
  protected val testPutFuture = putString("test.txt", "test")
  testPutFuture.failed.map {
    case exception:Throwable =>
      log.error(s"Failed to create test object in S3 bucket ${BUCKET} at key ${KEY_PREFIX}test.txt: ${exception.getMessage}")
      throw exception
  }

  override def preStart() = {
    Await.result(testPutFuture, TIMEOUT milliseconds)
    log.info(s"Ready to push messages to bucket ${BUCKET} with key prefix ${KEY_PREFIX}")
  }
  override def postStop() = {
    cancelDelayedFlush
    client.shutdown()
  }

  def receive = {
    case MutationWithInfo(mutation, _, _, Some(message: String)) =>
      log.debug(s"Received message: ${message}")

      cancelDelayedFlush

      messageBuffer += message
      messageBuffer.size match {
        case BATCH_SIZE => flush(sender())
        case _ => setDelayedFlush(sender())
      }

    case FlushRequest(origSender) =>
      flush(origSender)
  }

  protected def flush(origSender: ActorRef) = {
    val messageCount = messageBuffer.length
    log.debug(s"Flushing ${messageCount} messages to S3.")

    val now = DateTime.now
    val datePrefix = f"${now.getYear}/${now.getMonthOfYear}%02d/${now.getDayOfMonth}%02d"
    val key = s"${datePrefix}/${System.nanoTime}-${messageCount}.json"
    val s3Url = s"${BUCKET}/${KEY_PREFIX}${key}"
    val request = putString(key, getMessageBatch)

    request onComplete {
      case Success(result: PutObjectResult) =>
        log.info(s"Successfully saved ${messageCount} messages to ${s3Url}.")
        origSender ! akka.actor.Status.Success(s3Url)
      case Failure(exception) =>
        log.error(s"Failed to save ${messageCount} messages to ${s3Url}: ${exception.getMessage}")
        throw exception
    }
  }
}
