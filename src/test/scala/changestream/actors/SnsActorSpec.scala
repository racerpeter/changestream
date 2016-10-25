package changestream.actors

import akka.actor.Props
import akka.testkit.TestActorRef
import changestream.helpers.{Emitter, Config, Fixtures}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.language.postfixOps

class SnsActorSpec extends Emitter with Config {
  val actorRef = TestActorRef(Props(new SnsActor(awsConfig)))

  "When SnsActor receives a single valid message" should {
    "Immediately publish the message to SNS" in {
      actorRef ! message

      val result = expectMsgType[akka.actor.Status.Success](50000 milliseconds)
      result.status shouldBe a[String]
    }
  }

  "When SnsActor receives a message" should {
    "Should correctly publish the message when the topic contains interpolated database and/or tableName" in {
      val configWithInterpolation = ConfigFactory.
        parseString("aws.sns.topic = \"__integration_tests-{database}-{tableName}\"").
        withFallback(awsConfig)
      val snsWithInterpolation = TestActorRef(Props(new SnsActor(configWithInterpolation)))

      snsWithInterpolation ! message

      val result = expectMsgType[akka.actor.Status.Success](50000 milliseconds)
      result.status shouldBe a[String]
    }
  }

  "When SnsActor receives an invalid message" should {
    "Return a failure message, and throw an exception" in {
      actorRef ! INVALID_MESSAGE

      expectMsgType[akka.actor.Status.Failure](1000 milliseconds)
    }
  }
}
