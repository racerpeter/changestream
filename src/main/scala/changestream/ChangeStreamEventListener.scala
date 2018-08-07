package changestream

import akka.actor.{Actor, ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.util.Timeout
import changestream.actors.PositionSaver.{GetPositionRequest, GetPositionResponse, SaveCurrentPositionRequest, SavePositionRequest}
import changestream.actors._
import changestream.events._

import scala.concurrent.duration._
import com.github.shyiko.mysql.binlog.event._
import com.github.shyiko.mysql.binlog.BinaryLogClient.EventListener
import com.github.shyiko.mysql.binlog.event.EventType._
import org.slf4j.LoggerFactory
import com.typesafe.config.Config

import scala.concurrent.{Await, Future}

object ChangeStreamEventListener extends EventListener {
  protected val log = LoggerFactory.getLogger(getClass)
  protected val system = ActorSystem("changestream")

  protected val systemDatabases = Seq("information_schema", "mysql", "performance_schema", "sys")
  protected val whitelist: java.util.List[String] = new java.util.LinkedList[String]()
  protected val blacklist: java.util.List[String] = new java.util.LinkedList[String]()

  @volatile protected var positionSaver: Option[ActorRef] = None
  @volatile protected var emitter: Option[ActorRef] = None

  protected lazy val formatterActor = system.actorOf(Props(new JsonFormatterActor(getEmitterLoader)), name = "formatterActor")
  protected lazy val columnInfoActor = system.actorOf(Props(new ColumnInfoActor(_ => formatterActor)), name = "columnInfoActor")
  protected lazy val transactionActor = system.actorOf(Props(new TransactionActor(_ => columnInfoActor)), name = "transactionActor")

  /** Allows the configuration for the listener object to be set on startup.
    * The listener will look for whitelist, blacklist, and emitter settings.
    *
    * @param config
    */
  def setConfig(config: Config) = {
    whitelist.clear()
    blacklist.clear()

    if(config.hasPath("whitelist")) {
      config.getString("whitelist").split(',').foreach(whitelist.add(_))
      log.info(s"Using event whitelist: ${whitelist}")
    }
    else if(config.hasPath("blacklist")) {
      config.getString("blacklist").split(',').foreach(blacklist.add(_))
      log.info(s"Using event blacklist: ${blacklist}")
    }

    if(config.hasPath("position-saver.actor")) {
      createActor(config.getString("position-saver.actor"), "positionSaverActor", config).
        foreach(actorRef => setPositionSaver(actorRef))
    }

    if(config.hasPath("emitter")) {
      createActor(config.getString("emitter"), "emitterActor", getPositionSaverLoader, config).
        foreach(actorRef => setEmitter(actorRef))
    }
  }

  def getStoredPosition:Option[String] = {
    import akka.pattern.ask
    implicit val ec = system.dispatcher
    implicit val timeout = Timeout(5.seconds)
    positionSaver.flatMap { saver =>
      val getFuture = saver.ask(GetPositionRequest)
      val response = getFuture map {
        case GetPositionResponse(position) =>
          position
        case _ =>
          log.error("Failed to get position from position saver actor-- this should not happen!")
          None
      } recover {
        case _: java.util.concurrent.TimeoutException =>
          log.error("Timed out getting position from position saver actor!!")
          None
      }
      Await.result[Option[String]](response, 6.seconds)
    }
  }

  def setPosition(position: Option[String]) = {
    import akka.pattern.ask
    implicit val ec = system.dispatcher
    implicit val timeout = Timeout(5.seconds)

    positionSaver.map { saver =>
      val saveFuture = saver.ask(SavePositionRequest(position))
      val response = saveFuture map {
        case Some(position: String) =>
          Some(position)
        case _ =>
          log.error("Failed to set position in position saver actor-- this should not happen!")
          None
      } recover {
        case _: java.util.concurrent.TimeoutException =>
          log.error("Timed out getting position from position saver actor!!")
          None
      }
      Await.result[Option[String]](response, 6.seconds)
    }
  }

  def persistPosition = {
    import akka.pattern.ask
    implicit val ec = system.dispatcher
    implicit val timeout = Timeout(5.seconds)

    positionSaver match {
      case Some(saver) =>
        saver.ask(SaveCurrentPositionRequest) map {
          case Some(position: String) =>
            Some(position)
          case _ =>
            log.error("Failed to set position in position saver actor-- this should not happen!")
            None
        }
      case None =>
        log.error("Position saver actor is missing-- this should not happen!")
        Future { None }
    }
  }

  protected def getEmitterLoader: (ActorRefFactory => ActorRef) = _ => emitter match {
    case None => system.actorOf(Props(new StdoutActor(getPositionSaverLoader)), name = "emitterActor")
    case Some(emitter) => emitter
  }

  protected def getPositionSaverLoader: (ActorRefFactory => ActorRef) = _ => positionSaver match {
    case None => system.actorOf(Props(new PositionSaver()), name = "positionSaverActor")
    case Some(saver) => saver
  }

  /** Allows the position saver actor to be configured at runtime.
    *
    * Note: you must initialize the position saver before the emitter, or it will be ignored
    *
    * @param actor your actor ref
    */
  def setPositionSaver(actor: ActorRef) = positionSaver = Some(actor)

  /** Allows the emitter/producer actor to be configured at runtime.
    *
    * Note: you must initialize the emitter before the first event arrives, or it will be ignored
    *
    * @param actor your actor ref
    */
  def setEmitter(actor: ActorRef) = emitter = Some(actor)

  /** Sends binlog events to the appropriate changestream actor.
    *
    * @param binaryLogEvent The binlog event
    */
  def onEvent(binaryLogEvent: Event) = {
    log.debug(s"Received event: ${binaryLogEvent}")
    val changeEvent = getChangeEvent(binaryLogEvent)

    changeEvent match {
      case Some(e: TransactionEvent)  => transactionActor ! e
      case Some(e: MutationEvent)     =>
        transactionActor ! MutationWithInfo(
          e,
          getNextPosition(
            binaryLogEvent.getHeader[EventHeaderV4].getNextPosition
          )
        )
      case Some(e: AlterTableEvent)   => columnInfoActor ! e
      case None =>
        log.debug(s"Ignoring ${binaryLogEvent.getHeader[EventHeaderV4].getEventType} event.")
    }
  }

  def getNextPosition(eventNextPosition: Long): String = {
    ChangeStream.currentBinlogFilename + ":" + (eventNextPosition match {
      case 0L => ChangeStream.currentBinlogPosition.toString // if nextposition is 0, save the current position
      case _ => eventNextPosition.toString // otherwise, save the next position (i.e. advance to the next change)
    })
  }

  /** Returns the appropriate ChangeEvent case object given a binlog event object.
    *
    * @param event The java binlog listener event
    * @return The resulting ChangeEvent
    */
  def getChangeEvent(event: Event): Option[ChangeEvent] = {
    val header = event.getHeader[EventHeaderV4]

    header.getEventType match {
      case eventType if EventType.isRowMutation(eventType) =>
        getMutationEvent(event, header)

      case GTID =>
        Some(Gtid(event.getData[GtidEventData].getGtid))

      case XID =>
        Some(CommitTransaction)

      case QUERY =>
        parseQueryEvent(event.getData[QueryEventData])

      case FORMAT_DESCRIPTION =>
        val data = event.getData[FormatDescriptionEventData]
        log.info(s"Server version: ${data.getServerVersion}, binlog version: ${data.getBinlogVersion}")
        None

      // Known events that are safe to ignore
      case PREVIOUS_GTIDS => None
      case ROTATE => None
      case ROWS_QUERY => None
      case TABLE_MAP => None
      case ANONYMOUS_GTID => None
      case STOP => None

      case _ =>
        val message = s"Received unknown message: ${event}"
        log.error(message)
        throw new Exception(message)
    }
  }

  protected def getMutationEvent(event: Event, header: EventHeaderV4): Option[MutationEvent] = {
    val mutation = header.getEventType match {
      case e if EventType.isWrite(e) =>
        event.getData[Insert].copy(timestamp = header.getTimestamp)

      case e if EventType.isUpdate(e) =>
        event.getData[Update].copy(timestamp = header.getTimestamp)

      case e if EventType.isDelete(e) =>
        event.getData[Delete].copy(timestamp = header.getTimestamp)
    }

    shouldIgnore(mutation) match {
      case true =>
        log.debug(s"Ignoring event for table ${mutation.database}.${mutation.tableName}.")
        None
      case false =>
        Some(mutation)
    }
  }

  /** Returns a ChangeEvent case class instance representing the change indicated by
    * the given binlog QUERY event (either BEGIN, COMMIT, ROLLBACK, or ALTER...).
    *
    * @param queryData The QUERY event data
    * @return
    */
  protected def parseQueryEvent(queryData: QueryEventData): Option[ChangeEvent] = {
    queryData.getSql match {
      case sql if sql matches "(?i)^begin" =>
        Some(BeginTransaction)

      case sql if sql matches "(?i)^commit" =>
        Some(CommitTransaction)

      case sql if sql matches "(?i)^rollback" =>
        Some(RollbackTransaction)

      case sql if sql matches "(?i)^alter.*" =>
        /** Not(space, dot)+ OR backtick + Not(backtick, dot) + backtick OR "Not(", dot)" **/
        /** Does not currently support spaces or backticks in table or db names **/
        val dbOrTableName = "([^\\s\\.]+|`[^`\\.]+`|\"[^\"\\.]\")"
        val identifierRegex = s"(?i)^alter\\s+(ignore\\s+)?table\\s+($dbOrTableName(\\.$dbOrTableName)?).*".r

        var maybeIdentifier:Option[(String, String)] = None
        for { p <- identifierRegex findAllIn sql } p match {
          case identifierRegex(_, _, first, _, second) =>
            maybeIdentifier = Some(second match {
              case null => (queryData.getDatabase, unescapeIdentifier(first)) //scalastyle:ignore
              case _ => (unescapeIdentifier(first), unescapeIdentifier(second))
            })
        }

        maybeIdentifier.map({
          case (db, table) => Some(AlterTableEvent(db.toLowerCase, table.toLowerCase, sql))
        }).getOrElse(
          None
        )
      case sql =>
        None
    }
  }

  protected def unescapeIdentifier(escaped: String) = escaped.charAt(0) match {
    case '`' => escaped.substring(1, escaped.length - 1).replace("``", "`")
    case '"' => escaped.substring(1, escaped.length - 1).replace("\"\"", "\"")
    case _ => escaped
  }

  private def createActor(classString: String, actorName: String, args: Object*):Option[ActorRef] = {
    try {
      val constructor = Class.forName(classString).asInstanceOf[Class[Actor]].getDeclaredConstructors.head
      lazy val actorInstance = constructor.newInstance(args: _*).asInstanceOf[Actor]
      Some(system.actorOf(Props(actorInstance), name = actorName))
    }
    catch {
      case e: ClassNotFoundException =>
        log.error(s"Couldn't load emitter class ${classString} (ClassNotFoundException), using the default emitter.")
        None
    }
  }

  private def shouldIgnore(info: MutationEvent) = {
    if(systemDatabases.contains(info.database)) {
      true
    }
    else if(!whitelist.isEmpty) {
      tableMissingFromWhitelist(info.database, info.tableName)
    }
    else if(!blacklist.isEmpty) {
      tableInBlacklist(info.database, info.tableName)
    }
    else {
      false
    }
  }

  private def tableInBlacklist(database: String, table: String) = {
    tableInList(database, table, blacklist)
  }

  private def tableMissingFromWhitelist(database: String, table: String) = {
    !tableInList(database, table, whitelist)
  }

  private def tableInList(database: String, table: String, list: java.util.List[String]) = {
    if(list.isEmpty) {
      false
    }
    else if(list.contains(s"${database}.*") || list.contains(s"${database}.${table}")) {
      true
    }
    else {
      false
    }
  }
}
