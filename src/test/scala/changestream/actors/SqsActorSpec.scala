package changestream.actors

import akka.actor.Props
import akka.testkit.TestActorRef
import changestream.actors.SqsActor.BatchResult
import changestream.helpers.{Config, Emitter}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.language.postfixOps

class SqsActorSpec extends Emitter with Config {
  val actorRef = TestActorRef(Props(new SqsActor(awsConfig)))

  "When SqsActor receives a single valid message" should {
    "Add the message to the SQS queue in a batch of one" in {
      actorRef ! message

      val result = expectMsgType[akka.actor.Status.Success](50000 milliseconds)
      result.status shouldBe a[BatchResult]
      result.status.asInstanceOf[BatchResult].failed shouldBe empty
      result.status.asInstanceOf[BatchResult].queued should have length 1
    }
  }

  "When SqsActor receives multiple valid messages in quick succession" should {
    "Add the messages to the SQS queue in a batch of multiple" in {
      actorRef ! message
      actorRef ! message

      val result = expectMsgType[akka.actor.Status.Success](5000 milliseconds)
      result.status shouldBe a[BatchResult]
      result.status.asInstanceOf[BatchResult].failed shouldBe empty
      result.status.asInstanceOf[BatchResult].queued should have length 2
    }
  }

  "When SqsActor receives multiple valid messages in slow succession" should {
    "Add the messages to the SQS queue in multiple batches of one message" in {
      actorRef ! message
      Thread.sleep(500)
      actorRef ! message

      val result1 = expectMsgType[akka.actor.Status.Success](5000 milliseconds)
      val result2 = expectMsgType[akka.actor.Status.Success](5000 milliseconds)

      result1.status shouldBe a[BatchResult]
      result1.status.asInstanceOf[BatchResult].failed shouldBe empty
      result1.status.asInstanceOf[BatchResult].queued should have length 1

      result2.status shouldBe a[BatchResult]
      result2.status.asInstanceOf[BatchResult].failed shouldBe empty
      result2.status.asInstanceOf[BatchResult].queued should have length 1
    }
  }
}
