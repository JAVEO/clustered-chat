package actors

import play.api.libs.json._
import play.api.libs.json.Reads._
import reactivemongo.bson.BSONDateTime
import play.api.libs.functional.syntax._
import play.twirl.api.HtmlFormat

object ChatMessageWithCreationDate extends Helper {

  def create(topic: String, user: String, text: String, millis: Long) = new ChatMessageWithCreationDate(ChatMessage(topic, user, text), new java.util.Date(millis))

  object JsonConversionMode extends Enumeration {
    val Web, Mongo, WebWithoutUnescaping = Value
  }

  implicit def chatMessageWrites(implicit mode: JsonConversionMode.Value) = {
    new OWrites[ChatMessageWithCreationDate] {
      def writes(chatMessage: ChatMessageWithCreationDate): JsObject = mode match {
        case JsonConversionMode.Web => Json.obj(
            "topic" -> chatMessage.msg.topic,
            "user" -> chatMessage.msg.user,
            "text" -> multiLine(chatMessage.msg.text),
            "creationDate" -> chatMessage.creationDate.getTime)

        case JsonConversionMode.Mongo => Json.obj(
            "topic" -> chatMessage.msg.topic,
            "user" -> chatMessage.msg.user,
            "text" -> chatMessage.msg.text,
            "creationDate" -> Json.obj(
              "$date" -> chatMessage.creationDate.getTime))
      }
    }
  }

  implicit def chatMessageReads(implicit mode: JsonConversionMode.Value): Reads[ChatMessageWithCreationDate] = mode match {
    case JsonConversionMode.Web =>
      (
        (JsPath \ "topic").read[String] and
        (JsPath \ "user").read[String] and
        ((JsPath \ "text").read[String].map(unmultiLine _)) and
        (JsPath \ "creationDate").read[Long]
      )(ChatMessageWithCreationDate.create _)
    case JsonConversionMode.WebWithoutUnescaping =>
      (
        (JsPath \ "topic").read[String] and
        (JsPath \ "user").read[String] and
        (JsPath \ "text").read[String] and
        (JsPath \ "creationDate").read[Long]
      )(ChatMessageWithCreationDate.create _)
    case JsonConversionMode.Mongo =>
      (
        (JsPath \ "topic").read[String] and
        (JsPath \ "user").read[String] and
        (JsPath \ "text").read[String] and
        (JsPath \ "creationDate" \ "$date").read[Long]
      )(ChatMessageWithCreationDate.create _)
  }

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
    HtmlFormat.escape(text).body.replace("\n", "<br/>")
  }

  protected def unmultiLine(text: String) = {
    val replaced = text.replace("<br/>", "\n")
    unescape(replaced)
  }
  private def unescape(text: String): String = {
    val builder = new StringBuilder
    var i = 0
    val escapes = Map[String, String](
      "&lt;" -> "<",
      "&gt;" -> ">",
      "&quot;" -> "\"",
      "&#x27;" -> "'",
      "&amp;" -> "&")
    while (i < text.length) {
      for ((prefix, value) <- escapes if i < text.length && text.startsWith(prefix, i)) {
        builder.append(value)
        i += prefix.length
      }
      if (i < text.length) {
        builder.append(text.charAt(i))
        i += 1
      }
    }
    return builder.toString
  }

}
