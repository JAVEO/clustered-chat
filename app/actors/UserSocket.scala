package actors

import actors.UserSocket.Message
import actors.UserSocket.Message.messageReads
import akka.actor.{Actor, ActorLogging, ActorRef, Props, Cancellable}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Publish, Subscribe, Unsubscribe}
import akka.event.LoggingReceive
import play.api.libs.json.{Format, Writes, JsPath, JsValue, JsString, JsObject, JsArray, Json, JsNull, JsError, JsSuccess}
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

  object PagerQuery {

    val limit = 10

    def initial(topic: String) = new PagerQuery(topic, Direction.Older, new java.util.Date().getTime)

    object Direction extends Enumeration {
      type Direction = Value
      val Older, Newer = Value
      def withLowercaseName(name: String) = this.withName(name.capitalize)
    }

    implicit val directionFormat = new Format[Direction.Value] {
      def writes(d: Direction.Value) = JsString(d.toString.toLowerCase)
      def reads(json: JsValue) = json match {
        case JsNull => JsError()
        case _ => JsSuccess(Direction.withLowercaseName(json.as[String]))
      }
    }

    implicit val pagerQueryFormat = Json.format[PagerQuery]

  }

  case class PagerQuery(topic: String, direction: PagerQuery.Direction.Value, date: Long)

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

  case class SingleMessage(msg: ChatMessageWithCreationDate)

  object SingleMessage {

    implicit val chatMessagesListWrites: Writes[SingleMessage] = new Writes[SingleMessage] {
      def writes(singleMessage: SingleMessage): JsValue = {
        implicit val messageWriteMode = ChatMessageWithCreationDate.WriteMode.Web
        Json.obj(
          "type" -> "message",
          "msg" -> singleMessage.msg
        )
      }
    }

  }

  case class ChatMessagesListMessage(
    query: PagerQuery,
    isLast: Boolean,
    msgs: Seq[ChatMessageWithCreationDate])

  object ChatMessagesListMessage {
    import PagerQuery._

    implicit val chatMessagesListWrites: Writes[ChatMessagesListMessage] = new Writes[ChatMessagesListMessage] {
      def writes(chatMessages: ChatMessagesListMessage): JsValue = {
        implicit val messageWriteMode = ChatMessageWithCreationDate.WriteMode.Web
        Json.obj(
          "type" -> "messages",
          "messages" -> chatMessages.msgs,
          "query" -> Json.toJson(chatMessages.query),
          "isLast" -> chatMessages.isLast
        )
      }
    }

  }

  case object GetTopics
  case object NoTopicsFound
  case class NoMessagesFound(query: PagerQuery)

  case class MessagesPagerTimeout(query: PagerQuery)
  case object InitialTopicsTimeout
}

class UserSocket(uid: String, out: ActorRef, conf: play.api.Configuration) extends Actor with ActorLogging {
  import UserSocket._
  import scala.concurrent.ExecutionContext.Implicits.global

  val topicsTopic = conf.getString("my.special.string") + "topics"
  val messagesTopic = conf.getString("my.special.string") + "messages"
  var lastSubscribed: Option[String] = None
  var topicToSubscribe: Option[String] = None
  var scheduledTimeout: Option[Cancellable] = None
  var lastQuery: Option[PagerQuery] = None
  val dbService = DBService(context.system).instance

  val mediator = DistributedPubSub(context.system).mediator
  implicit val node = Cluster(context.system)

  context.system.scheduler.scheduleOnce(0 seconds, self, "init")

  def receive = {
    case "init" =>
      dbService ! GetTopics
      scheduledTimeout = Some(context.system.scheduler.scheduleOnce(3 seconds, self, InitialTopicsTimeout))
      context become waitingForInitialTopics
  }

  def waitingForInitialTopics = LoggingReceive {
    case t : TopicsListMessage =>
      mediator ! Subscribe(topicsTopic, self)
      out ! Json.toJson(t)
      context become basic
    case NoTopicsFound =>
      mediator ! Subscribe(topicsTopic, self)
      out ! Json.obj("type" -> "no topics found")
      context become basic
    case InitialTopicsTimeout =>
      mediator ! Subscribe(topicsTopic, self)
      out ! Json.obj("type" -> "initial topics timeout")
      context become basic
  } orElse basic

  def waitingForInitialMessages = waitingForMessages(true)

  def waitingForPagedMessages = waitingForMessages(false)

  def waitingForMessages(initial: Boolean) = LoggingReceive {
    case c : ChatMessagesListMessage if lastQuery == Some(c.query) =>
      if (initial) {
        //subscribeToTopic()
      }
      cancelTimeout()
      Thread.sleep(3000)
      out ! Json.toJson(c)
      context become basic
    case NoMessagesFound(q) if lastQuery == Some(q) =>
      if (initial) {
        //subscribeToTopic()
      }
      out ! Json.obj("type" -> "no messages found")
      cancelTimeout()
      Thread.sleep(3000)
      context become basic
    case MessagesPagerTimeout(q) if lastQuery == Some(q) =>
      /*
      if (initial) {
        subscribeToTopic()
      } else {
        out ! Json.obj("type" -> "messages pager timeout")
      }
      */
      out ! Json.obj("type" -> "messages pager timeout")
      context become basic
  } orElse basic

  def basic: Actor.Receive = LoggingReceive {
    case JsString(topicName) => mediator ! Publish(topicsTopic, TopicNameMessage(topicName))
    case js: JsValue =>
      ((js \ "type").as[String]) match {
        case "subscribe" =>
          val topic = (js \ "topic").as[String]
          if (topic != null) {
            unsubscribe()
            topicToSubscribe = Some(topic)
            subscribeToTopic()
            Thread.sleep(10000)
            val query = PagerQuery.initial(topic)
            lastQuery = Some(query)
            dbService ! query
            cancelTimeout()
            scheduledTimeout = Some(context.system.scheduler.scheduleOnce(3 seconds, self, MessagesPagerTimeout(query)))
            context become waitingForInitialMessages
          }
        case "message" =>
          js.validate[Message](messageReads)
            .map(message => (message.topic, Utility.escape(message.msg)))
            .foreach { case (topic, msg) => 
              val chatMessage = ChatMessage(topic, uid, msg).withCreationDate(new java.util.Date())
              mediator ! Publish(topic, chatMessage)
              mediator ! Publish(messagesTopic, chatMessage)
            }
        case "pager" =>
          js.validate[PagerQuery](PagerQuery.pagerQueryFormat)
            .foreach { q =>
              dbService ! q
              lastQuery = Some(q)
              cancelTimeout()
              scheduledTimeout = Some(context.system.scheduler.scheduleOnce(3 seconds, self, MessagesPagerTimeout(q)))
              context become waitingForPagedMessages
            }
      }
    case c @ ChatMessageWithCreationDate(ChatMessage(topic, _, _), _) if isSubscribedTo(topic) =>
      implicit val writeMode = ChatMessageWithCreationDate.WriteMode.Web
      out ! Json.toJson(SingleMessage(c))
    case t : TopicNameMessage => out ! Json.toJson(t)
  }

  def isSubscribedTo(topic: String): Boolean = lastSubscribed match {
    case None => false
    case Some(subscribedTopic) => subscribedTopic == topic
  }

  def unsubscribe() {
    lastSubscribed foreach { oldTopic =>
      mediator ! Unsubscribe(oldTopic, self)
    }
    lastSubscribed = None
  }

  def subscribeToTopic() {
    topicToSubscribe foreach { topic =>
      mediator ! Subscribe(topic, self)
      lastSubscribed = Some(topic)
    }
  }

  def cancelTimeout() {
    scheduledTimeout.foreach(_.cancel())
  }
}
