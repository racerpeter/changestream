package changestream.actors

import akka.actor.SupervisorStrategy.Escalate
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}
import akka.actor.{Actor, OneForOneStrategy}
import com.amazonaws.services.sns.AmazonSNSAsyncClient
import com.github.dwhjames.awswrap.sns.AmazonSNSScalaClient
import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.LoggerFactory

import scala.concurrent.Await

class SnsActor(config: Config = ConfigFactory.load().getConfig("changestream")) extends Actor {
  override val supervisorStrategy =
    OneForOneStrategy(loggingEnabled = true) {
      case _: Exception => Escalate
    }

  protected val log = LoggerFactory.getLogger(getClass)
  protected implicit val ec = context.dispatcher

  protected val TIMEOUT = config.getLong("aws.timeout")

  protected val snsTopic = config.getString("aws.sns.topic")
  protected val client = new AmazonSNSScalaClient(new AmazonSNSAsyncClient())
  protected val topicArn = client.createTopic(snsTopic)
  topicArn.failed.map { case exception:Throwable => throw exception }

  override def preStart() = {
    val arn = Await.result(topicArn, TIMEOUT milliseconds)
    log.info(s"Connected to SNS topic ${snsTopic} with ARN ${arn}")
  }

  def receive = {
    case message: String =>
      log.debug(s"Received message: ${message}")
      send(message)
    case _ =>
      log.error(s"Received invalid message.")
      sender() ! akka.actor.Status.Failure(new Exception("Received invalid message"))
  }

  protected def send(message: String) = {
    val origSender = sender()

    val request = for {
      topic <- topicArn
      req <- client.publish(
        topic.getTopicArn,
        message
      )
    } yield req

    request onComplete {
      case Success(result) =>
        log.debug(s"Successfully published message to ${snsTopic} (messageId ${result.getMessageId})")
        origSender ! akka.actor.Status.Success(result.getMessageId)
      case Failure(exception) =>
        log.error(s"Failed to publish to topic ${snsTopic}.", exception)
        origSender ! akka.actor.Status.Failure(exception)
    }
  }
}
