package changestream.actors

import java.io.File

import akka.actor.Props
import akka.testkit.TestActorRef
import changestream.actors.PositionSaver._
import changestream.helpers.{Config, Emitter}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.language.postfixOps

class PositionSaverSpec extends Emitter with Config {
  val tempFile = File.createTempFile("positionSaverSpec", "")

  val saverConfig = ConfigFactory.
    parseString(s"position-saver.file-path = ${tempFile.getPath}").
    withFallback(awsConfig)

  before {
    tempFile.delete()
  }

  after {
    tempFile.delete()
  }

  "When requesting the current position" should {
    "Respond with current position" in {
      val saver = TestActorRef(Props(classOf[PositionSaver], saverConfig))
      saver ! GetPositionRequest
      expectMsgType[GetPositionResponse](5000 milliseconds)
    }

    "Throw an exception when the save file location can't be read" in {
      val badConfig = ConfigFactory.
        parseString(s"position-saver.file-path = /").
        withFallback(awsConfig)
      val saver = TestActorRef(Props(classOf[PositionSaver], badConfig))

      saver ! GetPositionRequest
      expectNoMsg(2000 milliseconds)
    }

    "Throw an exception when the save file location can't be written" in {
      val badConfig = ConfigFactory.
        parseString(s"position-saver.file-path = /etc/hosts").
        withFallback(awsConfig)
      val saver = TestActorRef(Props(classOf[PositionSaver], badConfig))

      saver ! GetPositionRequest
      expectNoMsg(2000 milliseconds)
    }
  }

  "When setting the current position via override" should {
    "Immediately store the override position in memory" in {
      val saver = TestActorRef(Props(classOf[PositionSaver], saverConfig))

      saver ! GetPositionRequest
      val initialPosition = expectMsgType[GetPositionResponse](5000 milliseconds)
      initialPosition.position should be(None)

      saver ! SavePositionRequest(Some("position"))

      saver ! GetPositionRequest
      val savedPosition = expectMsgType[GetPositionResponse](5000 milliseconds)
      savedPosition.position should be(Some("position"))
    }

    "Immediately persist the override position to the temp file" in {
      val saver = TestActorRef(Props(classOf[PositionSaver], saverConfig))

      saver ! GetPositionRequest
      val initialPosition = expectMsgType[GetPositionResponse](5000 milliseconds)
      initialPosition.position should be(None)

      saver ! SavePositionRequest(Some("position"))

      val saverReloaded = TestActorRef(Props(classOf[PositionSaver], saverConfig))

      saverReloaded ! GetPositionRequest
      val savedPosition = expectMsgType[GetPositionResponse](5000 milliseconds)
      savedPosition.position should be(Some("position"))
    }

//    "Throw an exception when the save file location is invalid" in {
//      val tempDir = new File("/tmp/saver")
//      tempDir.exists() should be(false)
//
//      tempDir.mkdir()
//      val tempFile = File.createTempFile("saverBadFileTest", "", tempDir)
//
//      // initially the config is valid
//      val badConfig = ConfigFactory.
//        parseString(s"position-saver.file-path = ${tempFile.getPath}").
//        withFallback(awsConfig)
//      val saver = TestActorRef(Props(classOf[PositionSaver], badConfig))
//
//      saver ! GetPositionRequest
//      val initialPosition = expectMsgType[GetPositionResponse](5000 milliseconds)
//      initialPosition.position should be(None)
//
//      // then we delete the temp file's parent directory
//      tempDir.delete() // TODO can't seem to delete this directory
//      tempDir.exists() should be(false)
//
//      saver ! SavePositionRequest(Some("position"))
//      expectMsgType[akka.actor.Status.Failure](2000 milliseconds)
//    }
  }

  "When receiving a mutation" should {
    "Immediately persist the current position in memory" in {
      val saver = TestActorRef(Props(classOf[PositionSaver], saverConfig))

      saver ! GetPositionRequest
      val initialPosition = expectMsgType[GetPositionResponse](5000 milliseconds)
      initialPosition.position should be(None)

      saver ! EmitterResult("position")

      saver ! GetPositionRequest
      val savedPosition = expectMsgType[GetPositionResponse](5000 milliseconds)
      savedPosition.position should be(Some("position"))
    }

    "Persist the position when MAX_RECORDS has been reached" in {
      val configWithOneMaxRecord = ConfigFactory.
        parseString(s"position-saver.max-records = 1\nposition-saver.max-wait = 1000000").
        withFallback(saverConfig)
      val saverMaxRecordsOne = TestActorRef(Props(classOf[PositionSaver], configWithOneMaxRecord))

      saverMaxRecordsOne ! GetPositionRequest
      val initialPosition = expectMsgType[GetPositionResponse](5000 milliseconds)
      initialPosition.position should be(None)

      saverMaxRecordsOne ! EmitterResult("position")

      val saverReloaded = TestActorRef(Props(classOf[PositionSaver], configWithOneMaxRecord))

      saverReloaded ! GetPositionRequest
      val savedPosition = expectMsgType[GetPositionResponse](5000 milliseconds)
      savedPosition.position should be(Some("position"))
    }

    "Don't persist the position when MAX_RECORDS is 0 (disabled)" in {
      val configWithOneMaxRecord = ConfigFactory.
        parseString(s"position-saver.max-records = 0\nposition-saver.max-wait = 1000000").
        withFallback(saverConfig)
      val saverMaxRecordsOne = TestActorRef(Props(classOf[PositionSaver], configWithOneMaxRecord))

      saverMaxRecordsOne ! GetPositionRequest
      val initialPosition = expectMsgType[GetPositionResponse](5000 milliseconds)
      initialPosition.position should be(None)

      saverMaxRecordsOne ! EmitterResult("position")

      val saverReloaded = TestActorRef(Props(classOf[PositionSaver], configWithOneMaxRecord))

      saverReloaded ! GetPositionRequest
      val savedPosition = expectMsgType[GetPositionResponse](5000 milliseconds)
      savedPosition.position should be(None)
    }

    "Persist the position when MAX_RECORDS is reached twice (resetting the internal counter)" in {
      val configWithOneMaxRecord = ConfigFactory.
        parseString(s"position-saver.max-records = 1\nposition-saver.max-wait = 1000000").
        withFallback(saverConfig)
      val saverMaxRecordsOne = TestActorRef(Props(classOf[PositionSaver], configWithOneMaxRecord))

      saverMaxRecordsOne ! GetPositionRequest
      val initialPosition = expectMsgType[GetPositionResponse](5000 milliseconds)
      initialPosition.position should be(None)

      saverMaxRecordsOne ! EmitterResult("position")
      saverMaxRecordsOne ! EmitterResult("position2")

      val saverReloaded = TestActorRef(Props(classOf[PositionSaver], configWithOneMaxRecord))

      saverReloaded ! GetPositionRequest
      val savedPosition = expectMsgType[GetPositionResponse](5000 milliseconds)
      savedPosition.position should be(Some("position2"))
    }

    "When MAX_RECORDS has not been reached" should {
      "Don't persist the position when MAX_WAIT is set to 0 (disabled)" in {
        val configWithOneMaxRecord = ConfigFactory.
          parseString(s"position-saver.max-records = 2\nposition-saver.max-wait = 0").
          withFallback(saverConfig)
        val saverMaxRecordsOne = TestActorRef(Props(classOf[PositionSaver], configWithOneMaxRecord))

        saverMaxRecordsOne ! GetPositionRequest
        val initialPosition = expectMsgType[GetPositionResponse](5000 milliseconds)
        initialPosition.position should be(None)

        saverMaxRecordsOne ! EmitterResult("position")

        val saverReloaded = TestActorRef(Props(classOf[PositionSaver], configWithOneMaxRecord))

        saverReloaded ! GetPositionRequest
        val savedPosition = expectMsgType[GetPositionResponse](5000 milliseconds)
        savedPosition.position should be(None)
      }

      "Persist the position when timeout has expired" in {
        val configWithOneMaxRecord = ConfigFactory.
          parseString(s"position-saver.max-records = 2\nposition-saver.max-wait = 10").
          withFallback(saverConfig)
        val saverMaxRecordsOne = TestActorRef(Props(classOf[PositionSaver], configWithOneMaxRecord))

        saverMaxRecordsOne ! GetPositionRequest
        val initialPosition = expectMsgType[GetPositionResponse](5000 milliseconds)
        initialPosition.position should be(None)

        saverMaxRecordsOne ! EmitterResult("position")
        Thread.sleep(1000)

        val saverReloaded = TestActorRef(Props(classOf[PositionSaver], configWithOneMaxRecord))

        saverReloaded ! GetPositionRequest
        val savedPosition = expectMsgType[GetPositionResponse](5000 milliseconds)
        savedPosition.position should be(Some("position"))
      }

      "Don't persist the position when the timeout has not been reached" in {
        val configWithOneMaxRecord = ConfigFactory.
          parseString(s"position-saver.max-records = 2\nposition-saver.max-wait = 10000").
          withFallback(saverConfig)
        val saverMaxRecordsOne = TestActorRef(Props(classOf[PositionSaver], configWithOneMaxRecord))

        saverMaxRecordsOne ! GetPositionRequest
        val initialPosition = expectMsgType[GetPositionResponse](5000 milliseconds)
        initialPosition.position should be(None)

        saverMaxRecordsOne ! EmitterResult("position")
        Thread.sleep(1000)

        val saverReloaded = TestActorRef(Props(classOf[PositionSaver], configWithOneMaxRecord))

        saverReloaded ! GetPositionRequest
        val savedPosition = expectMsgType[GetPositionResponse](5000 milliseconds)
        savedPosition.position should be(None)
      }
    }
  }
}
