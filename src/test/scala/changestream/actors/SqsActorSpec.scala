package changestream.actors

import akka.actor.{ActorRefFactory, Props}
import akka.testkit.{TestActorRef, TestProbe}
import changestream.actors.PositionSaver.EmitterResult
import changestream.actors.SqsActor.BatchResult
import changestream.helpers.{Config, Emitter, Fixtures}

import scala.concurrent.duration._
import scala.language.postfixOps

class SqsActorSpec extends Emitter with Config {
  val probe = TestProbe()
  val maker = (_: ActorRefFactory) => probe.ref
  val actorRef = TestActorRef(Props(classOf[SqsActor], maker, awsConfig))

  val fooBaz = Fixtures.mutationWithInfo("insert", 1, 1, false, true, 42)._1.copy(nextPosition = "FOOBAZ", formattedMessage = Some("{json:'foobaz'}"))
  val bipBop = Fixtures.mutationWithInfo("insert", 1, 1, false, true, 43)._1.copy(nextPosition = "BIPBOOP", formattedMessage = Some("{json:'bipboop'}"))

  "When SqsActor receives a single valid message" should {
    "Add the message to the SQS queue in a batch of one" in {
      actorRef ! message

      val result = probe.expectMsgType[EmitterResult](5000 milliseconds)
      result.position should be(message.nextPosition)
      result.meta.get shouldBe a[BatchResult]
      result.meta.get.asInstanceOf[BatchResult].failed shouldBe empty
      result.meta.get.asInstanceOf[BatchResult].queued should have length 1
    }
  }

  "When SqsActor receives multiple valid messages in quick succession" should {
    "Add the messages to the SQS queue in a batch of multiple" in {
      actorRef ! message
      actorRef ! fooBaz

      val result = probe.expectMsgType[EmitterResult](5000 milliseconds)
      result.position should be(fooBaz.nextPosition)
      result.sequence should be(fooBaz.mutation.sequence)
      result.meta.get shouldBe a[BatchResult]
      result.meta.get.asInstanceOf[BatchResult].failed shouldBe empty
      result.meta.get.asInstanceOf[BatchResult].queued should have length 2
    }
  }

  "When SqsActor receives multiple valid messages in slow succession" should {
    "Add the messages to the SQS queue in multiple batches of one message" in {
      actorRef ! message
      Thread.sleep(500)
      actorRef ! fooBaz

      val result1 = probe.expectMsgType[EmitterResult](5000 milliseconds)
      val result2 = probe.expectMsgType[EmitterResult](5000 milliseconds)

      result1.position should be(message.nextPosition)
      result1.sequence should be(message.mutation.sequence)
      result1.meta.get shouldBe a[BatchResult]
      result1.meta.get.asInstanceOf[BatchResult].failed shouldBe empty
      result1.meta.get.asInstanceOf[BatchResult].queued should have length 1

      result2.position should be(fooBaz.nextPosition)
      result2.sequence should be(fooBaz.mutation.sequence)
      result2.meta.get shouldBe a[BatchResult]
      result2.meta.get.asInstanceOf[BatchResult].failed shouldBe empty
      result2.meta.get.asInstanceOf[BatchResult].queued should have length 1
    }
  }
}
