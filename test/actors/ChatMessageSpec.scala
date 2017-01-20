package actors

import org.specs2.mutable.Specification
import play.api.libs.json.Json

class ChatMessageSpec extends Specification {
  val input = """
                |{
                |    "type": "message",
                |    "topic": "main",
                |    "user": "John",
                |    "text": "Yupi!<br/>Bug has been fixed."
                |}
              """.stripMargin

  val chatMessageJson = Json.parse(input)

  val chatMessage = ChatMessage("main", "John", "Yupi!\nBug has been fixed.")

  "Chat message" should {
    "produce json" in {
      val jsonResult = Json.toJson(chatMessage)
      jsonResult must_== chatMessageJson
    }
  }
}
