package changestream

import com.github.shyiko.mysql.binlog.BinaryLogClient
import com.github.shyiko.mysql.binlog.BinaryLogClient.LifecycleListener
import org.slf4j.LoggerFactory

object ChangeStreamLifecycleListener extends LifecycleListener {
  protected val log = LoggerFactory.getLogger(getClass)

  def onConnect(client: BinaryLogClient) = {
    log.info(s"MySQL client connected!")
  }

  def onDisconnect(client: BinaryLogClient) = {
    log.info(s"MySQL binlog client was disconnected.")
  }

  def onEventDeserializationFailure(client: BinaryLogClient, ex: Exception) = {
    log.error(s"MySQL client failed to deserialize event:\n${ex}\n\n")
    ChangeStreamEventListener.shutdown()
  }

  def onCommunicationFailure(client: BinaryLogClient, ex: Exception) = {
    log.error(s"MySQL client communication failure:\n${ex}\n\n")

    if(ex.isInstanceOf[com.github.shyiko.mysql.binlog.network.ServerException]) {
      // If the server immediately replies with an exception, sleep for a bit
      Thread.sleep(5000)
    }
  }
}
