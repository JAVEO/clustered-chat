package actors

import actors.UserSocket.Message
import actors.UserSocket.Message.messageReads
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Publish, Subscribe, Unsubscribe}
import akka.event.LoggingReceive
import play.api.libs.json.{Writes, JsPath, JsValue, JsString, JsObject, JsArray, Json}
import play.twirl.api.HtmlFormat
import play.api.libs.functional.syntax._

import scala.xml.Utility
import scala.concurrent.duration._

import akka.cluster.Cluster

object UserSocket {
  def props(user: String, conf: play.api.Configuration)(out: ActorRef) = Props(new UserSocket(user, out, conf))

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

  case class TopicsListMessage(topics: Seq[String])
  object TopicsListMessage {
    implicit val topicsListMessageWrites = new Writes[TopicsListMessage] {
      def writes(topicsListMessage: TopicsListMessage): JsValue = {
        Json.obj(
          "type" -> "topics",
          "topics" -> JsArray(
            topicsListMessage.topics.map(name =>
                Json.obj(
                  "name" -> name,
                  "id" -> name.hashCode)))
        )
      }
    }
  }

  case class ChatMessagesListMessage(msgs: Seq[ChatMessageWithCreationDate])

  object ChatMessagesListMessage {
    implicit val chatMessagesListWrites = new Writes[ChatMessagesListMessage] {
      def writes(chatMessages: ChatMessagesListMessage): JsValue = {
        Json.obj(
          "type" -> "messages",
          "messages" -> JsArray(chatMessages.msgs.map(Json.toJson(_)))
        )
      }
    }
  }
}

class UserSocket(uid: String, out: ActorRef, conf: play.api.Configuration) extends Actor with ActorLogging {
  import UserSocket._
  import actors.DBServiceMessages._

  val topicsTopic = conf.getString("my.special.string") + "topics"
  val messagesTopic = conf.getString("my.special.string") + "messages"
  var lastSubscribed: Option[String] = None
  var initialHistory: Option[Set[ChatMessage]] = None
  val dbService = DBService(context.system).instance

  val mediator = DistributedPubSub(context.system).mediator
  implicit val node = Cluster(context.system)

  //mediator ! Subscribe(topicsTopic, self) // TODO first get the history, then subscribe
  dbService ! GetTopics

  def receive = LoggingReceive {
    case t : TopicsListMessage =>
      out ! Json.toJson(t)
      mediator ! Subscribe(topicsTopic, self)
    case c : ChatMessagesListMessage =>
      lastSubscribed foreach { topicNotSubscribedYet =>
        mediator ! Subscribe(topicNotSubscribedYet, self)
      }
      out ! Json.toJson(c)
    case JsString(topicName) => mediator ! Publish(topicsTopic, TopicNameMessage(topicName))
    case js: JsValue =>
      ((js \ "type").as[String]) match {
        case "subscribe" =>
          val topic = (js \ "topic").as[String]
          if (topic != null) {
            lastSubscribed foreach { oldTopic =>
              mediator ! Unsubscribe(oldTopic, self)
            }
            lastSubscribed = Some(topic)
            //mediator ! Subscribe(topic, self) // TODO first get the history, then subscribe
            dbService ! GetOldMessages(topic, new java.util.Date(), 10)
          }
        case "message" =>
          js.validate[Message](messageReads)
            .map(message => (message.topic, Utility.escape(message.msg)))
            .foreach { case (topic, msg) => 
              val chatMessage = ChatMessage(topic, uid, msg)
              mediator ! Publish(topic, chatMessage)
              mediator ! Publish(messagesTopic, chatMessage)
            }
      }
    case c @ ChatMessage(topic, _, _) if isSubscribedTo(topic) =>
      out ! Json.toJson(c)
    case t: TopicNameMessage => out ! Json.toJson(t)
  }

  def isSubscribedTo(topic: String): Boolean = lastSubscribed match {
    case None => false
    case Some(subscribedTopic) => subscribedTopic == topic
  }
}
