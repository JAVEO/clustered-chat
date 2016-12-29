package actors

import play.api.libs.json._
import play.api.libs.json.Reads._
import reactivemongo.bson.BSONDateTime
import play.api.libs.functional.syntax._
import play.twirl.api.HtmlFormat

object ChatMessageWithCreationDate {

  def create(topic: String, user: String, text: String, millis: Long) = new ChatMessageWithCreationDate(ChatMessage(topic, user, text), new java.util.Date(millis))

  implicit val chatMessageWrites = new OWrites[ChatMessageWithCreationDate] {
    def writes(chatMessage: ChatMessageWithCreationDate): JsObject = {
      Json.obj(
        "topic" -> chatMessage.msg.topic,
        "user" -> chatMessage.msg.user,
        "text" -> chatMessage.msg.text,
        "creationDate" -> Json.obj("$date" -> chatMessage.creationDate.getTime)
      )
    }
  }

  implicit val chatMessageReads: Reads[ChatMessageWithCreationDate] = (
      (JsPath \ "topic").read[String] and
      (JsPath \ "user").read[String] and
      (JsPath \ "text").read[String] and
      (JsPath \ "creationDate" \ "$date").read[Long]
    )(ChatMessageWithCreationDate.create _)

  implicit val orderingByDate: Ordering[ChatMessageWithCreationDate] = Ordering.by[ChatMessageWithCreationDate, java.util.Date](_.creationDate)
}


case class ChatMessageWithCreationDate(msg: ChatMessage, creationDate: java.util.Date)

case class ChatMessage(topic: String, user: String, text: String) {
  def withCreationDate(date: java.util.Date) = ChatMessageWithCreationDate(this, date)
  def createdNow = withCreationDate(new java.util.Date())
}

object ChatMessage {

  implicit val chatMessageWrites = new Writes[ChatMessage] {
    def writes(chatMessage: ChatMessage): JsValue = {
      Json.obj(
        "type" -> "message",
        "topic" -> chatMessage.topic,
        "user" -> chatMessage.user,
        "text" -> multiLine(chatMessage.text)
      )
    }
  }

  private def multiLine(text: String) = {
    HtmlFormat.raw(text).body.replace("\n", "<br/>")
  }
}

