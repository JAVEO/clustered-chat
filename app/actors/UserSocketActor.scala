package actors

import actors.UserSocketActor.Message
import actors.UserSocketActor.Message.messageReads
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.event.LoggingReceive
import play.api.libs.json.{JsValue, Json}

import scala.xml.Utility

class UserSocketActor(uid: String, room: ActorRef, out: ActorRef) extends Actor with ActorLogging {
  override def preStart() = {
    room ! Subscribe
  }

  def receive = LoggingReceive {
    case js: JsValue =>
      js.validate[Message](messageReads)
        .map(message => Utility.escape(message.msg))
        .foreach { room ! ChatMessage(uid, _)}

    case c:ChatMessage => out ! Json.toJson(c)
  }
}

object UserSocketActor {
  def props(chatRoom: ActorRef, user: String)(out: ActorRef) = Props(new UserSocketActor(user, chatRoom, out))

  case class Message(msg: String)

  object Message {
    implicit val messageReads = Json.reads[Message]
  }
}
