package changestream.actors

import java.util.UUID

import akka.actor.{ Actor, ActorRef, ActorRefFactory }
import changestream.events.MutationWithInfo

import collection.mutable
import changestream.events._
import org.slf4j.LoggerFactory

class TransactionActor(getNextHop: ActorRefFactory => ActorRef) extends Actor {
  protected val log = LoggerFactory.getLogger(getClass)
  protected val nextHop = getNextHop(context)

  /** Mutable State! */
  protected var transactionInfo: Option[TransactionInfo] = None
  protected var previousMutation: Option[MutationWithInfo] = None

  protected def setState(
                          info: Option[TransactionInfo] = transactionInfo,
                          prev: Option[MutationWithInfo] = previousMutation
                        ) = {
    transactionInfo = info
    previousMutation = prev
  }

  def receive = {
    case BeginTransaction =>
      log.debug(s"Received BeginTransacton")
      setState(info = Some(TransactionInfo(UUID.randomUUID.toString, 0)), prev = None)

    case Gtid(guid) =>
      log.debug(s"Received GTID for transaction: ${guid}")
      setState(info = transactionInfo.map(info => info.copy(gtid = guid)))

    case CommitTransaction =>
      log.debug(s"Received CommitTransacton")
      previousMutation.foreach { mutation =>
        log.debug(s"Adding transaction info and forwarding to the ${nextHop.path.name} actor")
        nextHop ! mutation.copy(transaction = transactionInfo.map { info =>
          info.copy(lastMutationInTransaction = true)
        })
      }
      setState(info = None, prev = None)

    case RollbackTransaction =>
      log.debug(s"Received RollbackTransacton")
      setState(info = None, prev = None)

    case event: MutationWithInfo =>
      log.debug(s"Received Mutation for tableId: ${event.mutation.tableId}")
      transactionInfo match {
        case None =>
          nextHop ! event
        case Some(info) =>
          previousMutation.foreach { mutation =>
            nextHop ! mutation.copy(transaction = transactionInfo)
          }

          setState(
            info = Some(TransactionInfo(info.gtid, info.rowCount + 1)),
            prev = Some(event)
          )
      }

    case _ =>
      throw new Exception("Invalid message received by TransactionActor")
  }
}
