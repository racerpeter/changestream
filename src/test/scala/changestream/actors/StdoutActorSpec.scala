package changestream.actors

import akka.actor.{ActorRefFactory, Props}
import akka.testkit.{TestActorRef, TestProbe}
import changestream.helpers.{Config, Emitter}

import scala.concurrent.duration._
import scala.language.postfixOps

class StdoutActorSpec extends Emitter with Config {
  val probe = TestProbe()
  val maker = (_: ActorRefFactory) => probe.ref
  val actorRef = TestActorRef(Props(classOf[StdoutActor], maker, awsConfig))

  "When StdoutActor receives a single valid message" should {
    "Immediately publish the message to SNS" in {
      actorRef ! message

      probe.expectMsgType[String](5000 milliseconds)
    }
  }
}
