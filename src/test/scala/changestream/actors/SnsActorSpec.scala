package changestream.actors

import akka.actor.Props
import akka.testkit.TestActorRef
import changestream.helpers.{Base, Config}

import scala.concurrent.duration._
import scala.language.postfixOps

class SnsActorSpec extends Base with Config {
  val actorRef = TestActorRef(Props(new SnsActor(awsConfig)))

  val INVALID_MESSAGE = 0

  "When SnsActor receives a single valid message" should {
    "Immediately publish the message to SNS" in {
      val jsonString = "{json:true}"
      actorRef ! jsonString

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
