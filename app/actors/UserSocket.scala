package actors

import actors.UserSocket.{ChatMessage, Message}
import actors.UserSocket.Message.messageReads
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Publish, Subscribe, Unsubscribe}
import akka.event.LoggingReceive
import play.api.libs.json.{Writes, JsValue, JsString, JsObject, Json}
import play.twirl.api.HtmlFormat

import scala.xml.Utility

object UserSocket {
  def props(user: String)(out: ActorRef) = Props(new UserSocket(user, out))

  case class Message(topic: String, msg: String)

  object Message {
    implicit val messageReads = Json.reads[Message]
  }

  case class TopicNameMessage(topicName: String)

  object TopicNameMessage {
    implicit val topicNameMessageWrites = new Writes[TopicNameMessage] {
      def writes(topicNameMessage: TopicNameMessage): JsValue = {
        Json.obj(
          "type" -> "topicName",
          "topicName" -> topicNameMessage.topicName,
          "topicId" -> topicNameMessage.topicName.hashCode
        )
      }
    }
  }

  case class ChatMessage(topic: String, user: String, text: String)

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
}

class UserSocket(uid: String, out: ActorRef) extends Actor with ActorLogging {
  import UserSocket._

  val topicsTopic = "topics"
  var lastSubscribed: Option[String] = None

  val mediator = DistributedPubSub(context.system).mediator

  mediator ! Subscribe(topicsTopic, self)

  def receive = LoggingReceive {
    case JsString(topicName) => mediator ! Publish(topicsTopic, TopicNameMessage(topicName))
    case js: JsValue =>
      ((js \ "type").as[String]) match {
        case "subscribe" =>
          val topic = (js \ "topic").as[String]
          mediator ! Subscribe(topic, self)
          lastSubscribed foreach { oldTopic =>
            mediator ! Unsubscribe(oldTopic, self)
          }
          lastSubscribed = Some(topic)
        case "message" =>
          js.validate[Message](messageReads)
            .map(message => (message.topic, Utility.escape(message.msg)))
            .foreach { case (topic, msg) => {mediator ! Publish(topic, ChatMessage(topic, uid, msg))}}
      }

    case c:ChatMessage => out ! Json.toJson(c)
    case t:TopicNameMessage => out ! Json.toJson(t)
  }
}
