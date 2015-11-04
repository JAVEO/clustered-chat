package actors

import actors.UserSocket.{ChatMessage, Message}
import actors.UserSocket.Message.messageReads
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Publish, Subscribe}
import akka.event.LoggingReceive
import play.api.libs.json.{Writes, JsValue, Json}
import play.twirl.api.HtmlFormat

import scala.xml.Utility

object UserSocket {
  def props(user: String)(out: ActorRef) = Props(new UserSocket(user, out))

  case class Message(msg: String)

  object Message {
    implicit val messageReads = Json.reads[Message]
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
}

class UserSocket(uid: String, out: ActorRef) extends Actor with ActorLogging {

  val topic = "chat"

  val mediator = DistributedPubSub(context.system).mediator

  mediator ! Subscribe(topic, self)

  def receive = LoggingReceive {
    case js: JsValue =>
      js.validate[Message](messageReads)
        .map(message => Utility.escape(message.msg))
        .foreach { msg => mediator ! Publish(topic, ChatMessage(uid, msg))}

    case c:ChatMessage => out ! Json.toJson(c)
  }
}
