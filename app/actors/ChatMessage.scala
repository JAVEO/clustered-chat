package actors

import play.api.libs.json._
import play.api.libs.json.Reads._
import reactivemongo.bson.BSONDateTime
import play.api.libs.functional.syntax._
import play.twirl.api.HtmlFormat

object ChatMessageWithCreationDate extends Helper {

  def create(topic: String, user: String, text: String, millis: Long) = new ChatMessageWithCreationDate(ChatMessage(topic, user, text), new java.util.Date(millis))

  object WriteMode extends Enumeration {
    val Web, Mongo = Value
  }

  implicit def chatMessageWrites(implicit mode: WriteMode.Value) = {
    new OWrites[ChatMessageWithCreationDate] {
      def writes(chatMessage: ChatMessageWithCreationDate): JsObject = mode match {
        case WriteMode.Web => Json.obj(
            "topic" -> chatMessage.msg.topic,
            "user" -> chatMessage.msg.user,
            "text" -> multiLine(chatMessage.msg.text),
            "creationDate" -> chatMessage.creationDate.getTime)

        case WriteMode.Mongo => Json.obj(
            "topic" -> chatMessage.msg.topic,
            "user" -> chatMessage.msg.user,
            "text" -> chatMessage.msg.text,
            "creationDate" -> Json.obj(
              "$date" -> chatMessage.creationDate.getTime))
      }
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
  override def toString() = {
    val limit = 20
    val shortText = if (text == null || text.length <= limit) text
      else text.take(limit) + "..."
    s"${this.getClass.getName}(${topic}, ${user}, ${shortText})"
  }
}

object ChatMessage extends Helper {

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

}

trait Helper {

  protected def multiLine(text: String) = {
    HtmlFormat.raw(text).body.replace("\n", "<br/>")
  }

}
