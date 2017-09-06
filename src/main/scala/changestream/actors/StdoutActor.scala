package changestream.actors

import akka.actor.Actor
import changestream.events.MutationWithInfo
import com.typesafe.config.{Config, ConfigFactory}

class StdoutActor(config: Config = ConfigFactory.load().getConfig("changestream")) extends Actor {
  def receive = {
    case MutationWithInfo(mutation, _, _, Some(message: String)) =>
      println(message)
      sender() ! akka.actor.Status.Success(message)
  }
}
