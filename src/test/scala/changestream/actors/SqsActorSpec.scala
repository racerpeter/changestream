package changestream.actors

import akka.actor.{ActorRefFactory, Props}
import akka.testkit.{TestActorRef, TestProbe}
import changestream.actors.SqsActor.BatchResult
import changestream.helpers.{Config, Emitter}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.language.postfixOps

class SqsActorSpec extends Emitter with Config {
  val probe = TestProbe()
  val maker = (_: ActorRefFactory) => probe.ref
  val actorRef = TestActorRef(Props(classOf[SqsActor], maker, awsConfig))

  "When SqsActor receives a single valid message" should {
    "Add the message to the SQS queue in a batch of one" in {
      actorRef ! message

      val result = probe.expectMsgType[BatchResult](5000 milliseconds)
      result.failed shouldBe empty
      result.queued should have length 1
    }
  }

  "When SqsActor receives multiple valid messages in quick succession" should {
    "Add the messages to the SQS queue in a batch of multiple" in {
      actorRef ! message
      actorRef ! message

      val result = probe.expectMsgType[BatchResult](5000 milliseconds)
      result.asInstanceOf[BatchResult].failed shouldBe empty
      result.asInstanceOf[BatchResult].queued should have length 2
    }
  }

  "When SqsActor receives multiple valid messages in slow succession" should {
    "Add the messages to the SQS queue in multiple batches of one message" in {
      actorRef ! message
      Thread.sleep(500)
      actorRef ! message

      val result1 = probe.expectMsgType[BatchResult](5000 milliseconds)
      val result2 = probe.expectMsgType[BatchResult](5000 milliseconds)

      result1 shouldBe a[BatchResult]
      result1.asInstanceOf[BatchResult].failed shouldBe empty
      result1.asInstanceOf[BatchResult].queued should have length 1

      result2 shouldBe a[BatchResult]
      result2.asInstanceOf[BatchResult].failed shouldBe empty
      result2.asInstanceOf[BatchResult].queued should have length 1
    }
  }
}
