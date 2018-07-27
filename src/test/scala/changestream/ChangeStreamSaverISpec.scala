package changestream

import java.io.{File, FileOutputStream, IOException, OutputStreamWriter}
import java.nio.charset.StandardCharsets

import akka.actor.{ActorRef, ActorRefFactory, Props}
import akka.testkit.TestProbe
import changestream.actors.PositionSaver.EmitterResult
import changestream.actors.{PositionSaver, StdoutActor}
import changestream.events.{Delete, Insert, MutationWithInfo, Update}
import changestream.helpers.{App, Config, Database}
import com.github.mauricio.async.db.RowData
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.io.Source
import scala.util.Random

// TODO cleanup and refactor -- improve binlog comparison test
class ChangeStreamSaverISpec extends Database with Config {
  // Bootstrap the config
  val tempFile = File.createTempFile("positionSaverISpec", "")
  System.setProperty("config.resource", "test.conf")
  System.setProperty("MYSQL_SERVER_ID", Random.nextLong.toString)
  System.setProperty("POSITION_SAVER_FILE_PATH", tempFile.getPath)
  System.setProperty("POSITION_SAVER_MAX_RECORDS", "2") // use 1 for first three specs, 2 for TERM spec
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

  def getApp = new Thread {
    override def run = ChangeStream.main(Array())
    override def interrupt = {
      ChangeStream.terminateActorSystemAndWait
      super.interrupt
    }
  }

  "when starting up" should {
    "start reading from the last known position when there is a last known position and no override is passed" in {
      writeBinLogPosition(getLiveBinLogPosition)

      val startingPosition = getLiveBinLogPosition
      val storedPosition = getStoredBinLogPosition
      println("LIVEPOS!!!!!! " + getLiveBinLogPosition)
      println ("STORPOS!!!!!! " + getStoredBinLogPosition)

      // write something before we start
      queryAndWait(INSERT)

      val positionAfterFirstQuery = getLiveBinLogPosition
      println("LIVEPOS!!!!!! " + getLiveBinLogPosition)
      println ("STORPOS!!!!!! " + getStoredBinLogPosition)

      init // this creates the emitter and saver actors, and they persist through the lifecycle of the test
      val app = getApp
      app.start
      Thread.sleep(5000)

      // write something after we start
      queryAndWait(UPDATE)

      val mutation = expectMutation
      println("RESULT!!!!!! " + mutation.nextPosition)
      Thread.sleep(1000)

      mutation.mutation.isInstanceOf[Insert] shouldBe (true)
      (mutation.nextPosition < positionAfterFirstQuery) shouldBe (true)
      (mutation.nextPosition > startingPosition) shouldBe (true)
      (mutation.nextPosition > storedPosition) shouldBe (true)

      println("LIVEPOS!!!!!! " + getLiveBinLogPosition)
      println("STORPOS!!!!!! " + getStoredBinLogPosition)
      app.interrupt
    }

    "start from 'real time' when there is no last known position" in {
      val startingPosition = getLiveBinLogPosition
      println("LIVEPOS!!!!!! " + getLiveBinLogPosition)
      println ("STORPOS!!!!!! " + getStoredBinLogPosition)

      // write something before we start
      queryAndWait(INSERT)

      val positionAfterFirstQuery = getLiveBinLogPosition
      println("LIVEPOS!!!!!! " + getLiveBinLogPosition)
      println ("STORPOS!!!!!! " + getStoredBinLogPosition)

      init
      val app = getApp
      app.start
      Thread.sleep(5000)

      // write something after we start
      queryAndWait(UPDATE)

      val mutation = expectMutation
      println("RESULT!!!!!! " + mutation.nextPosition)
      Thread.sleep(1000)

      mutation.mutation.isInstanceOf[Update] shouldBe (true)
      (mutation.nextPosition > startingPosition) shouldBe (true)
      (mutation.nextPosition > positionAfterFirstQuery) shouldBe (true)

      println("LIVEPOS!!!!!! " + getLiveBinLogPosition)
      println("STORPOS!!!!!! " + getStoredBinLogPosition)
      app.interrupt
    }

    "start from override when override is passed" in {
      writeBinLogPosition(getLiveBinLogPosition)

      val startingPosition = getLiveBinLogPosition
      val storedPosition = getStoredBinLogPosition

      println("LIVEPOS!!!!!! " + getLiveBinLogPosition)
      println ("STORPOS!!!!!! " + getStoredBinLogPosition)

      // make sure we aren't at the saved position
      queryAndWait(INSERT)

      val overridePosition = getLiveBinLogPosition
      System.setProperty("OVERRIDE_POSITION", overridePosition)
      ConfigFactory.invalidateCaches()

      println("OVERRIDE!!!!!! " + overridePosition)

      // write something before we start
      queryAndWait(UPDATE)

      val positionAfterOverride = getLiveBinLogPosition
      println("LIVEPOS!!!!!! " + getLiveBinLogPosition)
      println ("STORPOS!!!!!! " + getStoredBinLogPosition)

      init
      val app = getApp
      app.start
      Thread.sleep(5000)

      // write something after we start
      queryAndWait(DELETE)

      val positionAfterDelete = getLiveBinLogPosition
      println("LIVEPOS!!!!!! " + getLiveBinLogPosition)
      println ("STORPOS!!!!!! " + getStoredBinLogPosition)

      val mutation = expectMutation
      println("RESULT!!!!!! " + mutation.nextPosition)
      Thread.sleep(1000)

      mutation.mutation.isInstanceOf[Update] shouldBe (true)
      (mutation.nextPosition > startingPosition) shouldBe (true)
      (mutation.nextPosition > overridePosition) shouldBe (true)
      (mutation.nextPosition < positionAfterOverride) shouldBe (true)
      (mutation.nextPosition < positionAfterDelete) shouldBe (true)

      println("LIVEPOS!!!!!! " + getLiveBinLogPosition)
      println("STORPOS!!!!!! " + getStoredBinLogPosition)
      app.interrupt
    }

    "exit gracefully when a TERM signal is received" in {
      val startingPosition = getStoredBinLogPosition
      println("LIVEPOS!!!!!! " + getLiveBinLogPosition)
      println ("STORPOS!!!!!! " + getStoredBinLogPosition)

      init
      val app = getApp
      app.start
      Thread.sleep(5000)

      // write something after we start
      queryAndWait(UPDATE)

      app.interrupt

      val termSavedPosition = getStoredBinLogPosition
      println("LIVEPOS!!!!!! " + getLiveBinLogPosition)
      println("STORPOS!!!!!! " + getStoredBinLogPosition)

      startingPosition shouldNot be(termSavedPosition)
    }
  }
}

