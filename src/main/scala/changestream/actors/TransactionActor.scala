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
  protected var mutationCount: Long = 0
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
      mutationCount = 0
      setState(info = Some(TransactionInfo(UUID.randomUUID.toString)), prev = None)

    case Gtid(guid) =>
      log.debug(s"Received GTID for transaction: ${guid}")
      setState(info = Some(TransactionInfo(guid)))

    case _: TransactionEvent =>
      log.debug(s"Received Commit/Rollback")
      previousMutation.foreach { mutation =>
        log.debug(s"Adding transaction info and forwarding to the ${nextHop.path.name} actor")
        nextHop ! mutation.copy(transaction = transactionInfo.map { info =>
          info.copy(rowCount = mutationCount,lastMutationInTransaction = true)
        })
      }
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
          mutationCount += event.mutation.rows.length;
          setState(prev = Some(event))
      }

    case _ =>
      throw new Exception("Invalid message received by TransactionActor")
  }
}
