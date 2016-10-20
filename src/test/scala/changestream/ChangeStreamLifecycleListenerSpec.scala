package changestream

import changestream.helpers.Base
import com.github.shyiko.mysql.binlog.BinaryLogClient

class ChangeStreamLifecycleListenerSpec extends Base {
  val client = new BinaryLogClient("foo", "bar")
  val ex = new Exception("failed")

  "ChangeStreamLifecycleListenerSpec" should {
    "onConnect should not throw" in {
      ChangeStreamLifecycleListener.onConnect(client)
    }
    "onDisconnect should not throw" in {
      ChangeStreamLifecycleListener.onDisconnect(client)
    }
    "onEventDeserializationFailure should throw" in {
      assertThrows[Exception] {
        ChangeStreamLifecycleListener.onEventDeserializationFailure(client, ex)
      }
    }
    "onCommunicationFailure should not throw" in {
      ChangeStreamLifecycleListener.onCommunicationFailure(client, ex)
    }
  }
}
