package changestream.actors

import java.text.{DateFormat, SimpleDateFormat}
import java.util
import java.util.TimeZone

import akka.actor.{Actor, ActorRef, ActorRefFactory, Props}
import akka.pattern.{ask, pipe}
import changestream.events._
import com.typesafe.config.{Config, ConfigFactory}
import spray.json._
import DefaultJsonProtocol._
import akka.util.Timeout
import changestream.actors.EncryptorActor.Plaintext
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.collection.immutable.ListMap

object JsonFormatterActor {
  val dateFormatter: DateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
  dateFormatter.setTimeZone(TimeZone.getTimeZone("UTC"))

  /**
    * Current columnType to java type mapping is following:
    * <pre>
    * {@link ColumnType#TINY}: Integer
    * {@link ColumnType#SHORT}: Integer
    * {@link ColumnType#LONG}: Integer
    * {@link ColumnType#INT24}: Integer
    * {@link ColumnType#YEAR}: Integer
    * {@link ColumnType#ENUM}: Integer
    * {@link ColumnType#SET}: Long
    * {@link ColumnType#LONGLONG}: Long
    * {@link ColumnType#FLOAT}: Float
    * {@link ColumnType#DOUBLE}: Double
    * {@link ColumnType#BIT}: java.util.BitSet
    * {@link ColumnType#DATETIME}: java.util.Date
    * {@link ColumnType#DATETIME_V2}: java.util.Date
    * {@link ColumnType#NEWDECIMAL}: java.math.BigDecimal
    * {@link ColumnType#TIMESTAMP}: java.sql.Timestamp
    * {@link ColumnType#TIMESTAMP_V2}: java.sql.Timestamp
    * {@link ColumnType#DATE}: java.sql.Date
    * {@link ColumnType#TIME}: java.sql.Time
    * {@link ColumnType#TIME_V2}: java.sql.Time
    * {@link ColumnType#VARCHAR}: String
    * {@link ColumnType#VAR_STRING}: String
    * {@link ColumnType#STRING}: String
    * {@link ColumnType#BLOB}: byte[]
    * {@link ColumnType#GEOMETRY}: byte[]
    * </pre>
    *
    */
  def getJsValueOrNone(javaVal: java.io.Serializable):Any = javaVal match {
    case v: util.BitSet => v.size match {
      case 1 => // return single bit as true/false value
        v.get(0).toJson
      case _ =>
        (0 to v.size - 1).map(v.get(_)).toJson //return many bits as an array of true/false
    }
    case timestamp: java.sql.Timestamp =>
      JsNumber(timestamp.getTime)
    case time: java.sql.Time =>
      JsString(time.toString)
    case date: java.sql.Date =>
      JsString(date.toString)
    case d: java.util.Date =>
      JsString(dateFormatter.format(d))
    case s: String =>
      JsString(s)
    case v: java.lang.Float =>
      JsNumber(v.toFloat)
    case v: java.lang.Double =>
      JsNumber(v)
    case v: java.math.BigDecimal =>
      JsNumber(v)
    case v: java.lang.Number =>
      JsNumber(v.longValue)
    case null => //scalastyle:ignore
      JsNull

    // unknown/unsupported data type
    case _ =>
      None
  }
}

class JsonFormatterActor (
                           getNextHop: ActorRefFactory => ActorRef,
                           config: Config = ConfigFactory.load().getConfig("changestream")
                         ) extends Actor {
  import JsonFormatterActor._

  protected val nextHop = getNextHop(context)
  protected val log = LoggerFactory.getLogger(getClass)

  protected val includeData = config.getBoolean("include-data")
  protected val encryptData = if(!includeData) false else config.getBoolean("encryptor.enabled")

  protected lazy val encryptorActor = context.actorOf(Props(new EncryptorActor(config.getConfig("encryptor"))), name = "encryptorActor")
  protected implicit val TIMEOUT = Timeout(config.getLong("encryptor.timeout") milliseconds)
  protected implicit val ec = context.dispatcher

  def receive = {
    case message: MutationWithInfo if message.columns.isDefined => {
      log.debug(s"Received ${message.mutation} for table ${message.mutation.database}.${message.mutation.tableName}")

      val primaryKeys = message.columns.get.columns.collect({ case col if col.isPrimary => col.name })
      val rowData = getRowData(message)
      val oldRowData = getOldRowData(message)
      val txInfo = transactionInfo(message)

      rowData.indices.foreach({ idx =>
        val row = rowData(idx)
        val oldRow = oldRowData.map(_(idx))
        val pkInfo = ListMap(primaryKeys.map({
          case k:String => k -> row.getOrElse(k, JsNull)
        }):_*)

        val payload =
          getJsonHeader(message, pkInfo, row, idx, rowData.length) ++
          txInfo ++
          getJsonRowData(row) ++
          updateInfo(oldRow)
        val json = JsObject(payload)

        if(encryptData) {
          log.debug(s"Encrypting JSON event and sending to the ${nextHop.path.name} actor")
          val encryptRequest = Plaintext(json, Seq("row_data", "old_row_data"))
          val cipherJson = ask(encryptorActor, encryptRequest).mapTo[JsValue].map(_.prettyPrint)
          cipherJson pipeTo nextHop
        }
        else {
          log.debug(s"Sending JSON event to the ${nextHop.path.name} actor")
          nextHop ! json.prettyPrint
        }
      })
    }
  }

  protected def getRowData(message: MutationWithInfo) = {
    val columns = message.columns.get.columns
    val mutation = message.mutation

    mutation.rows.map(row =>
      ListMap(columns.indices.map({
        case idx if mutation.includedColumns.get(idx) =>
          columns(idx).name -> getJsValueOrNone(row(idx))
      }).collect({
        case (k:String, v:JsValue) => k -> v
      }):_*)
    )
  }

  protected def getOldRowData(message: MutationWithInfo) = {
    val columns = message.columns.get.columns
    val mutation = message.mutation

    mutation match {
      case update:Update =>
        Some(update.oldRows.map(row =>
          ListMap(columns.indices.map({
            case idx if mutation.includedColumns.get(idx) =>
              columns(idx).name -> getJsValueOrNone(row(idx))
          }).collect({
            case (k:String, v:JsValue) => k -> v
          }):_*)
        ))
      case _ => None
    }
  }

  protected def transactionInfo(message: MutationWithInfo): ListMap[String, JsValue] = {
    message.transaction match {
      case Some(txn) => ListMap(
        "transaction" -> JsObject(
          "id" -> txn.guid.toJson,
          "row_count" -> txn.rowCount.toJson
        )
      )
      case None => ListMap.empty[String, JsValue]
    }
  }

  protected def getJsonHeader(
                              message: MutationWithInfo,
                              pkInfo: ListMap[String, JsValue],
                              rowData: ListMap[String, JsValue],
                              currentRow: Long,
                              rowsTotal: Long
                             ): ListMap[String, JsValue] = {
    ListMap(
      "mutation" -> JsString(message.mutation.toString),
      "sequence" -> JsNumber(message.mutation.sequence + currentRow),
      "database" -> JsString(message.mutation.database),
      "table" -> JsString(message.mutation.tableName),
      "query" -> JsObject(
        "timestamp" -> JsNumber(message.mutation.timestamp),
        "sql" -> JsString(message.mutation.sql.getOrElse("")),
        "row_count" -> JsNumber(rowsTotal),
        "current_row" -> JsNumber(currentRow + 1)
      ),
      "primary_key" -> JsObject(pkInfo)
    )
  }

  protected def getJsonRowData(rowData: ListMap[String, JsValue]): ListMap[String, JsValue] = includeData match {
    case true => ListMap("row_data" -> JsObject(rowData))
    case false => ListMap.empty[String, JsValue]
  }

  protected def updateInfo(oldRowData: Option[ListMap[String, JsValue]]): ListMap[String, JsValue] = includeData match {
    case true => oldRowData.map({ row => ListMap("old_row_data" -> JsObject(row)) }).getOrElse(ListMap.empty[String, JsValue])
    case false => ListMap.empty[String, JsValue]
  }
}
