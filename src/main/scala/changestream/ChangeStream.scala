package changestream

import java.io.IOException

import akka.Done
import akka.actor.{ActorSystem, Props}
import com.github.shyiko.mysql.binlog.BinaryLogClient
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import akka.pattern.ask
import akka.io.IO
import akka.util.Timeout
import changestream.actors.ControlInterfaceActor
import spray.can.Http

object ChangeStream extends App {
  protected implicit val system = ActorSystem("changestream")
  protected implicit val ec = system.dispatcher
  protected implicit val timeout = Timeout(10 seconds)

  protected val log = LoggerFactory.getLogger(getClass)
  protected val config = ConfigFactory.load().getConfig("changestream")
  protected val mysqlHost = config.getString("mysql.host")
  protected val mysqlPort = config.getInt("mysql.port")
  protected val client = new BinaryLogClient(
    mysqlHost,
    mysqlPort,
    config.getString("mysql.user"),
    config.getString("mysql.password")
  )

  /** Start the HTTP server for status and control **/
  protected val controlHost = config.getString("control.host")
  protected val controlPort = config.getInt("control.port")
  protected val controlActor = system.actorOf(Props[ControlInterfaceActor], "control-interface")
  protected val controlBind = Http.Bind(
    listener = controlActor,
    interface = controlHost,
    port = controlPort
  )
  protected val controlFuture = IO(Http).ask(controlBind).map {
    case Http.Bound(address) =>
      log.info(s"Control interface bound to ${address}")
    case Http.CommandFailed(cmd) =>
      log.warn(s"Control interface could not bind to ${controlHost}:${controlPort}, ${cmd.failureMessage}")
  }

  @volatile protected var isPaused = false

  /** Gracefully handle application shutdown from
    *  - Normal program exit
    *  - TERM signal
    *  - System reboot/shutdown
    */
  sys.addShutdownHook(terminateActorSystemAndWait)

  /** Every changestream instance must have a unique server-id.
    *
    * http://dev.mysql.com/doc/refman/5.7/en/replication-setup-slaves.html#replication-howto-slavebaseconfig
    */
  client.setServerId(config.getLong("mysql.server-id"))

  /** If we lose the connection to the server retry every `changestream.mysql.keepalive` milliseconds. **/
  client.setKeepAliveInterval(config.getLong("mysql.keepalive"))

  /** Register the objects that will receive `onEvent` calls and deserialize data **/
  ChangeStreamEventListener.setConfig(config)
  client.registerEventListener(ChangeStreamEventListener)
  client.setEventDeserializer(ChangestreamEventDeserializer)

  /** Register the object that will receive BinaryLogClient connection lifecycle events **/
  client.registerLifecycleListener(ChangeStreamLifecycleListener)

  getConnected

  def serverName = s"${mysqlHost}:${mysqlPort}"
  def clientId = client.getServerId
  def currentBinlogFilename = client.getBinlogFilename
  def currentBinlogPosition = client.getBinlogPosition
  def isConnected = client.isConnected

  def connect() = {
    if(!client.isConnected()) {
      isPaused = false
      Future { getConnected }
      true
    }
    else {
      false
    }
  }

  def disconnect() = {
    if(client.isConnected()) {
      isPaused = true
      client.disconnect()
      ChangeStreamEventListener.persistPosition
      true
    }
    else {
      false
    }
  }

  def reset() = {
    if(!client.isConnected()) {
      ChangeStreamEventListener.setPosition(None)
      Future { getConnected }
      true
    }
    else {
      false
    }
  }

  def terminateActorSystemAndWait = {
    log.info("Shutting down...")

    disconnect()

    val shutdownFuture = IO(Http).ask(Http.CloseAll) flatMap { _ =>
      system.terminate()
      system.whenTerminated flatMap { _ =>
        // Do a final save, after we allow the actor system to exit cleanly
        ChangeStreamEventListener.persistPosition.map(_ => Done)
      }
    }

    Await.result(shutdownFuture, 60 seconds)
  }

  def getConnected = {
    log.info(s"Starting changestream...")

    val overridePosition = System.getProperty("OVERRIDE_POSITION")
    System.setProperty("OVERRIDE_POSITION", "") // clear override after initial boot
    if(overridePosition != null && overridePosition.length > 0) { //scalastyle:ignore
      log.info(s"Overriding starting binlog position with OVERRIDE_POSITION=${overridePosition}")
      ChangeStreamEventListener.setPosition(Some(overridePosition))
    }

    ChangeStreamEventListener.getStoredPosition match {
      case Some(position) =>
        log.info(s"Setting starting binlog position at ${position}")
        val Array(fileName, posLong) = position.split(":")
        client.setBinlogFilename(fileName)
        client.setBinlogPosition(java.lang.Long.valueOf(posLong))
      case None =>
        client.setBinlogFilename(null) //scalastyle:ignore
        client.setBinlogPosition(4L)
    }

    while(!isPaused && !client.isConnected) {
      try {
        client.connect()
      }
      catch {
        case e: IOException =>
          log.error("Failed to connect to MySQL to stream the binlog, retrying...", e)
          Thread.sleep(5000)
        case e: Exception =>
          log.error("Failed to connect, exiting.", e)
          terminateActorSystemAndWait
          sys.exit(1)
      }
    }
  }
}
