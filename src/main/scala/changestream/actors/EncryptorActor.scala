package changestream.actors

import java.nio.charset.Charset
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

import akka.actor.Actor
import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.LoggerFactory
import spray.json._
import DefaultJsonProtocol._

object EncryptorActor {
  case class Plaintext(message: JsObject, fieldsToEncrypt: Seq[String])
  case class Ciphertext(message: JsObject, fieldsToDecrypt: Seq[String])
}

class EncryptorActor (
                        config: Config = ConfigFactory.load().getConfig("changestream.encryptor")
                      ) extends Actor {
  import EncryptorActor._

  protected val log = LoggerFactory.getLogger(getClass)

  private val charset = Charset.forName("UTF-8")
  private val decoder = Base64.getDecoder
  private val encoder = Base64.getEncoder

  private val cipher = config.getString("cipher")
  private val decodedKey = decoder.decode(config.getString("key"))
  private val originalKey = new SecretKeySpec(decodedKey, 0, decodedKey.length, cipher)
  private val encryptEngine = Cipher.getInstance(cipher)
  private val decryptEngine = Cipher.getInstance(cipher)

  override def preStart() = {
    encryptEngine.init(Cipher.ENCRYPT_MODE, originalKey)
    decryptEngine.init(Cipher.DECRYPT_MODE, originalKey)
  }

  def receive = {
    case Plaintext(message, fieldsToEncrypt) =>
      val result = message.fields.map({
        case (k:String, plaintextValue:JsValue) if fieldsToEncrypt.contains(k) =>
          val plaintextBytes = plaintextValue.compactPrint.getBytes(charset)
          val cipherText = encryptEngine.doFinal(plaintextBytes)
          val v = encoder.encodeToString(cipherText)
          k -> JsString(v)
        case (k:String, v:JsValue) =>
          k -> v
      }).toJson

      sender() ! result

    case Ciphertext(message, fieldsToDecrypt) =>
      try {
        val result = message.fields.map({
          case (k:String, JsString(ciphertextValue)) if fieldsToDecrypt.contains(k) =>
            val ciphertextBytes = decoder.decode(ciphertextValue)
            val plaintextBytes = decryptEngine.doFinal(ciphertextBytes)
            val v = new String(plaintextBytes, charset).parseJson
            k -> v
          case (k:String, v:JsValue) =>
            k -> v
        }).toJson

        sender() ! result
      }
      catch {
        case e:Exception => sender() ! akka.actor.Status.Failure(e)
      }
  }
}
