package changestream.actors

import akka.actor.Props
import akka.testkit.TestActorRef
import changestream.actors.EncryptorActor._
import changestream.helpers.{Base, Config}
import org.scalacheck._
import spray.json._

class EncryptorActorSpec extends Base with Config {
  val encryptorActor = TestActorRef(Props(classOf[EncryptorActor], testConfig.getConfig("changestream.encryptor")))

  def sourceObject(a: String, b: String) = JsObject(
    "no_encrypt" -> JsString(a),
    "no_encrypt_hash" -> JsObject("a" -> JsNumber(1), "b" -> JsNumber(2)),
    "do_encrypt" -> JsString(b),
    "do_encrypt_hash" -> JsObject("a" -> JsNumber(1), "b" -> JsNumber(2))
  )
  val CRYPT_FIELDS = Seq("do_encrypt", "do_encrypt_hash")

  "When encrypting/decrypting data" should {
    "expect encrypt -> decrypt to result in same plaintext for a single message" in {
      Prop.forAll { s: String =>
        val source = sourceObject(s, s)
        encryptorActor ! Plaintext(source, CRYPT_FIELDS)
        val encryptResponse = expectMsgType[String]

        encryptorActor ! Ciphertext(encryptResponse.parseJson.asJsObject, CRYPT_FIELDS)
        val decryptResponse = expectMsgType[JsObject]

        source.compactPrint == decryptResponse.compactPrint
      }.check
    }

    "expect decrypt of invalid ciphertext to result in failure message" in {
      encryptorActor ! Ciphertext(sourceObject("a", "b"), CRYPT_FIELDS)
      expectMsgType[akka.actor.Status.Failure]
    }
  }
}
