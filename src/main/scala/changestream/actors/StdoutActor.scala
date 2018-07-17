package changestream.actors

import akka.actor.{Actor, ActorRef, ActorRefFactory}
import changestream.events.MutationWithInfo
import com.typesafe.config.{Config, ConfigFactory}

class StdoutActor(getNextHop: ActorRefFactory => ActorRef,
                  config: Config = ConfigFactory.load().getConfig("changestream")) extends Actor {
  protected val nextHop = getNextHop(context)

  def receive = {
    case MutationWithInfo(mutation, _, _, Some(message: String)) =>
      println(message)
      nextHop ! "TODO position"
  }
}
