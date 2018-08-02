package changestream

import java.io.{File, FileOutputStream, OutputStreamWriter}
import java.nio.charset.StandardCharsets

import akka.actor.Props
import akka.testkit.TestProbe
import changestream.actors.PositionSaver._
import changestream.actors.{PositionSaver, StdoutActor}
import changestream.events.{Delete, Insert, MutationWithInfo, Update}
import changestream.helpers.{Config, Database}
import com.github.mauricio.async.db.RowData
import com.typesafe.config.ConfigFactory
import scala.language.postfixOps
import akka.pattern.ask

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.io.Source
import scala.util.Random

// TODO cleanup and refactor -- improve binlog comparison test
class ChangeStreamSaverISpec extends Database with Config {
  // Bootstrap the config
  val tempFile = File.createTempFile("positionSaverISpec", ".pos")
  System.setProperty("config.resource", "test.conf")
  System.setProperty("MYSQL_SERVER_ID", Random.nextLong.toString)
  System.setProperty("POSITION_SAVER_FILE_PATH", tempFile.getPath)
  System.setProperty("POSITION_SAVER_MAX_RECORDS", "2")
  System.setProperty("POSITION_SAVER_MAX_WAIT", "100000")
  ConfigFactory.invalidateCaches()

  // Wire in the probes
  lazy val positionSaver = system.actorOf(Props(new PositionSaver()), name = "positionSaverActor")
  lazy val emitterProbe = TestProbe()
  lazy val emitter = system.actorOf(Props(new StdoutActor(_ => positionSaver)), name = "emitterActor")

  def init = {
    ChangeStreamEventListener.setPositionSaver(positionSaver)
    ChangeStreamEventListener.setEmitter(emitterProbe.ref)
  }

  def flushProbe = while(emitterProbe.msgAvailable) { emitterProbe.receiveOne(1000 milliseconds)}

  override def afterAll(): Unit = {
    tempFile.delete()
    super.afterAll()
  }

  def expectMutation:MutationWithInfo = {
    val message = emitterProbe.expectMsgType[MutationWithInfo](5000.milliseconds)
    emitterProbe.forward(emitter)
    message
  }

  def getLiveBinLogPosition: String = {
    val result = Await.result(connection.sendQuery("show master status;"), 5000.milliseconds)
    result.rows match {
      case Some(resultSet) => {
        val row : RowData = resultSet.head
        val file = row("File").asInstanceOf[String]
        val position = row("Position").asInstanceOf[Long]
        s"${file}:${position}"
      }
      case None =>
        throw new Exception("Couldn't get binlog position")
    }
  }

  def getStoredBinLogPosition: String = {
    val bufferedSource = Source.fromFile(tempFile, "UTF-8")
    val position = bufferedSource.getLines.mkString
    bufferedSource.close
    position
  }

  def writeBinLogPosition(position: String) = {
    val saverOutputStream = new FileOutputStream(tempFile)
    val saverWriter = new OutputStreamWriter(saverOutputStream, StandardCharsets.UTF_8)
    saverWriter.write(position)
    saverWriter.close()
  }

  def getApp = {
    init
    flushProbe
    new Thread {
      override def run = ChangeStream.main(Array())
      override def interrupt = {
        ChangeStream.terminateActorSystemAndWait
        super.interrupt
      }
    }
  }

  val app = getApp
  app.start
  Thread.sleep(5000)

  "when starting up" should {
    "start from 'real time' when there is no last known position" in {
      ChangeStream.disconnect()
      Await.result(positionSaver ? SavePositionRequest(None), 1000 milliseconds)

      queryAndWait(INSERT)

      ChangeStream.connect()
      Thread.sleep(5000)

      queryAndWait(UPDATE)

      expectMutation.mutation shouldBe a[Update]
    }

    "start reading from the last known position when there is a last known position and no override is passed" in {
      ChangeStream.disconnect()
      Await.result(positionSaver ? SavePositionRequest(Some(getLiveBinLogPosition)), 1000 milliseconds)

      queryAndWait(INSERT)

      ChangeStream.connect()
      Thread.sleep(5000)

      queryAndWait(UPDATE)

      expectMutation.mutation shouldBe a[Insert]
      expectMutation.mutation shouldBe a[Update]
    }

    "start from override when override is passed" in {
      ChangeStream.disconnect()
      Await.result(positionSaver ? SavePositionRequest(Some(getLiveBinLogPosition)), 1000 milliseconds)

      // this event should be skipped due to the override
      queryAndWait(INSERT)

      val overridePosition = getLiveBinLogPosition
      System.setProperty("OVERRIDE_POSITION", overridePosition)
      ConfigFactory.invalidateCaches()

      // this event should arrive first
      queryAndWait(UPDATE)

      ChangeStream.connect()
      Thread.sleep(5000)

      // advance the live position to be "newer" than the override (should arrive second)
      queryAndWait(DELETE)

      expectMutation.mutation shouldBe a[Update]
      expectMutation.mutation shouldBe a[Delete]

      System.setProperty("OVERRIDE_POSITION", "")
      ConfigFactory.invalidateCaches()
    }

    "exit gracefully when a TERM signal is received" in {
      ChangeStream.disconnect()
      Await.result(positionSaver ? SavePositionRequest(Some(getLiveBinLogPosition)), 1000 milliseconds)

      val startingPosition = getStoredBinLogPosition

      ChangeStream.connect()
      Thread.sleep(5000)

      queryAndWait(INSERT) // should not persist immediately because of the max events = 2
      val insertMutation = expectMutation
      insertMutation.mutation shouldBe a[Insert]
      Thread.sleep(1000)

      getStoredBinLogPosition should be(startingPosition)

      ChangeStream.disconnect()
      Thread.sleep(1000)

      getStoredBinLogPosition should be(insertMutation.nextPosition)

      queryAndWait(UPDATE) // should not immediately persist
      Thread.sleep(1000)

      ChangeStream.connect()
      Thread.sleep(5000)

      queryAndWait(DELETE) // should persist because it is the second event processed by the saver
      queryAndWait(INSERT) // should not immediately persist
      Thread.sleep(1000)

      expectMutation.mutation shouldBe a[Update]
      expectMutation.mutation shouldBe a[Delete]

      //at this point, our saved position is not fully up to date
      val finalMutation = expectMutation
      finalMutation.mutation shouldBe a[Insert]
      getStoredBinLogPosition shouldNot be(finalMutation.nextPosition)

      app.interrupt
      Thread.sleep(5000)

      //should save any pending position pre-exit
      getStoredBinLogPosition should be(finalMutation.nextPosition)
    }
  }
}

