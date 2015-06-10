package actors

import javax.inject.Singleton

import akka.actor._
import akka.event.LoggingReceive
import play.api.libs.json.{Json, JsValue, Writes}
import play.libs.Akka
import play.twirl.api.HtmlFormat

@Singleton
class ChatRoomActor extends Actor with ActorLogging {
  var users = Set[ActorRef]()

  def receive = LoggingReceive {
    case Subscribe =>
      users += sender
      context watch sender

    case Terminated(user) =>
      users -= user

    case m: ChatMessage =>
      users foreach { _ ! m }
  }
}

case class ChatMessage(user: String, text: String)

object ChatMessage {
  implicit val chatMessageWrites = new Writes[ChatMessage] {
    def writes(chatMessage: ChatMessage): JsValue = {
      Json.obj(
        "type" -> "message",
        "user" -> chatMessage.user,
        "text" -> multiLine(chatMessage.text)
      )
    }
  }

  private def multiLine(text: String) = {
    HtmlFormat.raw(text).body.replace("\n", "<br/>")
  }
}
object Subscribe
