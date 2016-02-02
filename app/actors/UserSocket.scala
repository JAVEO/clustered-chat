package actors

import actors.UserSocket.Message
import actors.UserSocket.Message.messageReads
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.event.LoggingReceive
import play.api.libs.json.{Writes, JsPath, JsValue, JsString, JsObject, JsArray, Json}
import play.api.libs.functional.syntax._

import scala.xml.Utility
import scala.concurrent.duration._

import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.Replicator
import akka.cluster.ddata.Replicator._
import akka.cluster.ddata.GSet
import akka.cluster.ddata.GSetKey
import akka.cluster.ddata.LWWRegister
import akka.cluster.ddata.LWWRegisterKey

object UserSocket {
  def props(user: String)(out: ActorRef) = Props(new UserSocket(user, out))
  def topicMsgKey(topic: String) = LWWRegisterKey[ChatMessage](topic + "-lwwreg")

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
          "topics" -> JsArray(topicsListMessage.topics.map(JsString(_)))
        )
      }
    }
  }

  case class ChatMessagesListMessage(msgs: Seq[ChatMessage])

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

class UserSocket(uid: String, out: ActorRef) extends Actor with ActorLogging {
  import UserSocket._

  val topicsKey = GSetKey[String]("topics")
  var lastSubscribed: Option[String] = None
  var initialHistory: Option[Set[ChatMessage]] = None

  val replicator = DistributedData(context.system).replicator
  implicit val node = Cluster(context.system)

  replicator ! Get(topicsKey, ReadMajority(timeout = 5.seconds))

  def receive = LoggingReceive {
    case g @ GetSuccess(key, req) if key == topicsKey =>
      val data = g.get(topicsKey).elements.toSeq
      out ! Json.toJson(TopicsListMessage(data))
      replicator ! Subscribe(topicsKey, self)
      context become afterTopics
    case NotFound(_, _) =>
      replicator ! Subscribe(topicsKey, self)
      context become afterTopics
    case GetFailure(key, req) if key == topicsKey =>
      replicator ! Get(topicsKey, ReadMajority(timeout = 5.seconds))
  }

  def afterTopics = LoggingReceive {
    case c @ Changed(key) if key == topicsKey =>
      val data = c.get[GSet[String]](topicsKey).elements.toSeq
      out ! Json.toJson(TopicsListMessage(data))
    case c @ Changed(LWWRegisterKey(topic)) =>
      for {
        subscribedTopic <- lastSubscribed if (subscribedTopic + "-lwwreg").equals(topic)
      } {
        val chatMessage = c.get(LWWRegisterKey[ChatMessage](topic)).value
        initialHistory foreach { historySet =>
          if (!historySet(chatMessage))
            out ! Json.toJson(chatMessage)
        }
      }
    case g @ GetSuccess(GSetKey(topic), req) =>
      for {
        subscribedTopic <- lastSubscribed if subscribedTopic.equals(topic)
      } {
        val elements = g.get(GSetKey[ChatMessage](topic)).elements
        initialHistory = Some(elements)
        val data = elements.toSeq.sortWith(_.created.getTime < _.created.getTime)
        out ! Json.toJson(ChatMessagesListMessage(data))
        replicator ! Subscribe(topicMsgKey(topic), self)
      }
    case g @ NotFound(GSetKey(topic), req) =>
      for {
        subscribedTopic <- lastSubscribed if subscribedTopic.equals(topic)
      } {
        initialHistory = Some(Set.empty[ChatMessage])
        replicator ! Subscribe(topicMsgKey(topic), self)
      }
    case g @ GetFailure(GSetKey(topic), req) =>
      for {
        subscribedTopic <- lastSubscribed if subscribedTopic.equals(topic)
      } {
        initialHistory = Some(Set.empty[ChatMessage])
        replicator ! Subscribe(key = topicMsgKey(topic), self)
      }
    case JsString(topicName) => 
      replicator ! Update(topicsKey, GSet.empty[String], WriteLocal) {
        set => set + topicName
      }
      replicator ! FlushChanges
    case js: JsValue =>
      ((js \ "type").as[String]) match {
        case "subscribe" =>
          val topic = (js \ "topic").as[String]
          if (topic != null) {
            lastSubscribed foreach { oldTopic =>
              replicator ! Unsubscribe(topicMsgKey(oldTopic), self)
            }
            lastSubscribed = Some(topic)
            replicator ! Get(GSetKey(topic), ReadMajority(timeout = 5.seconds))
          }
        case "message" =>
          js.validate[Message](messageReads)
            .map(message => (message.topic, Utility.escape(message.msg)))
            .foreach { case (topic, msg) => 
              val chatMessage = ChatMessage(topic, uid, msg, new java.util.Date())
              replicator ! Update(GSetKey[ChatMessage](topic), GSet.empty[ChatMessage], WriteLocal) {
                _ + chatMessage
              }
              replicator ! Update(topicMsgKey(topic), LWWRegister[ChatMessage](null), WriteLocal) {
                reg => reg.withValue(chatMessage)
              }
              replicator ! FlushChanges
            }
      }
  }
}
