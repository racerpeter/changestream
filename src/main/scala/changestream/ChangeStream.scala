package changestream

import java.io.IOException
import java.util.concurrent.TimeoutException

import com.github.shyiko.mysql.binlog.BinaryLogClient
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.concurrent.ExecutionContext.Implicits.global

object ChangeStream extends App {
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

  /** Every changestream instance must have a unique server-id.
    * http://dev.mysql.com/doc/refman/5.7/en/replication-setup-slaves.html#replication-howto-slavebaseconfig
    */
  client.setServerId(config.getLong("mysql.server-id"))

  /** If we lose the connection to the server retry every `changestream.mysql.keepalive` milliseconds. **/
  client.setKeepAliveInterval(config.getLong("mysql.keepalive"))

  ChangeStreamEventListener.setConfig(config)
  ChangestreamEventDeserializerConfig.setConfig(config)

  ChangeStreamEventListener.startControlServer(config)

  client.registerEventListener(ChangeStreamEventListener)
  client.setEventDeserializer(ChangeStreamEventDeserializer)
  client.registerLifecycleListener(ChangeStreamLifecycleListener)


  getConnected

  def serverName = s"${mysqlHost}:${mysqlPort}"
  def clientId = client.getServerId
  def isConnected = client.isConnected

  def getConnectedAndWait = Await.result(getConnected, 60.seconds)
  def disconnectClient = client.disconnect()

  def getConnected = {
    log.info(s"Starting changestream...")

    val overridePosition = System.getProperty("OVERRIDE_POSITION")
    System.setProperty("OVERRIDE_POSITION", "") // clear override after initial boot

    val getPositionFuture = overridePosition match {
      case overridePosition:String if overridePosition.length > 0 =>
        log.info(s"Overriding starting binlog position with OVERRIDE_POSITION=${overridePosition}")
        ChangeStreamEventListener.setPosition(Some(overridePosition))
      case _ =>
        ChangeStreamEventListener.getStoredPosition
    }

    getPositionFuture.map { position =>
      setBinlogClientPosition(position)
      getInternalClientConnected
    }
  }

  protected def setBinlogClientPosition(position: Option[String]) = position match {
    case Some(position) =>
      log.info(s"Setting starting binlog position at ${position}")
      val Array(fileName, posLong) = position.split(":")
      client.setBinlogFilename(fileName)
      client.setBinlogPosition(java.lang.Long.valueOf(posLong))
    case None =>
      log.info(s"Starting binlog position in real time")
      client.setBinlogFilename(null) //scalastyle:ignore
      client.setBinlogPosition(4L)
  }

  protected def getInternalClientConnected = {
    while(!client.isConnected) {
      try {
        client.connect(5000)
      }
      catch {
        case e: IOException =>
          log.error("Failed to connect to MySQL to stream the binlog, retrying in 5 seconds...", e)
          Thread.sleep(5000)
        case e: TimeoutException =>
          log.error("Timed out connecting to MySQL to stream the binlog, retrying in 5 seconds...", e)
          Thread.sleep(5000)
        case e: Exception =>
          log.error("Failed to connect, exiting...", e)
          Await.result(ChangeStreamEventListener.shutdownAndExit(1), 60.seconds)
      }
    }
  }
}
