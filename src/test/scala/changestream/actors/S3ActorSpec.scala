package changestream.actors

import akka.actor.{ActorRefFactory, Props}
import akka.testkit.{TestActorRef, TestProbe}
import changestream.actors.PositionSaver.EmitterResult
import changestream.helpers.{Config, Emitter, Fixtures}

import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory

import scala.language.postfixOps

class S3ActorSpec extends Emitter with Config {
  val probe = TestProbe()
  val maker = (_: ActorRefFactory) => probe.ref
  val s3Config = ConfigFactory.
    parseString("aws.s3.batch-size = 2, aws.s3.flush-timeout = 1000").
    withFallback(awsConfig)
  val actorRef = TestActorRef(Props(classOf[S3Actor], maker, s3Config))

  val fooBaz = Fixtures.mutationWithInfo("insert", 1, 1, false, true, 42)._1.copy(nextPosition = "FOOBAZ", formattedMessage = Some("{json:'foobaz'}"))
  val bipBop = Fixtures.mutationWithInfo("insert", 1, 1, false, true, 43)._1.copy(nextPosition = "BIPBOOP", formattedMessage = Some("{json:'bipboop'}"))


  "When S3Actor receives a single valid message" should {
    "Add the message to S3 in a batch of one" in {
      actorRef ! message

      val result = probe.expectMsgType[EmitterResult](5000 milliseconds)
      result.position should be(message.nextPosition)
      result.sequence should be(message.mutation.sequence)
      result.meta.get.asInstanceOf[String] should endWith ("-1.json")
    }
  }

  "When S3Actor receives multiple valid messages in quick succession" should {
    "Add the messages to S3 in a batch of many" in {
      actorRef ! message
      actorRef ! fooBaz

      val result = probe.expectMsgType[EmitterResult](5000 milliseconds)
      result.position should be(fooBaz.nextPosition)
      result.sequence should be(fooBaz.mutation.sequence)
      result.meta.get.asInstanceOf[String] should endWith ("-2.json")
    }
  }

  "When S3Actor receives multiple valid messages in slow succession" should {
    "Add the messages to the S3 queue in multiple batches of one message" in {
      actorRef ! message
      Thread.sleep(2000)
      actorRef ! fooBaz

      val result1 = probe.expectMsgType[EmitterResult](5000 milliseconds)
      val result2 = probe.expectMsgType[EmitterResult](5000 milliseconds)
      result1.position should be(message.nextPosition)
      result1.sequence should be(message.mutation.sequence)
      result1.meta.get.asInstanceOf[String] should endWith ("-1.json")
      result2.position should be(fooBaz.nextPosition)
      result2.sequence should be(fooBaz.mutation.sequence)
      result2.meta.get.asInstanceOf[String] should endWith ("-1.json")
    }
  }

  "When S3Actor receives multiple valid messages that exceed the flush size" should {
    "Add the messages to the S3 queue in multiple batches" in {
      actorRef ! message
      actorRef ! fooBaz
      actorRef ! bipBop

      val result1 = probe.expectMsgType[EmitterResult](5000 milliseconds)
      val result2 = probe.expectMsgType[EmitterResult](5000 milliseconds)
      result1.position should be(fooBaz.nextPosition)
      result1.sequence should be(fooBaz.mutation.sequence)
      result1.meta.get.asInstanceOf[String] should endWith ("-2.json")
      result2.position should be(bipBop.nextPosition)
      result2.sequence should be(bipBop.mutation.sequence)
      result2.meta.get.asInstanceOf[String] should endWith ("-1.json")
    }
  }
}
