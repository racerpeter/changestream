package changestream.helpers

class Emitter extends Base {
  val (messageNoJson, _, _) = Fixtures.mutationWithInfo("insert", 1, 1, false, true, 1)
  val message = messageNoJson.copy(formattedMessage = Some("{json:true}"))
  val INVALID_MESSAGE = 0
}
