package actors

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Cancellable, Identify, ActorIdentity}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Publish, Subscribe, Unsubscribe}
import play.api.libs.json.{Format, Writes, JsPath, JsValue, JsString, JsObject, JsArray, Json, JsNull, JsError, JsSuccess}
import play.twirl.api.HtmlFormat
import play.api.libs.functional.syntax._

import scala.xml.Utility
import scala.concurrent.duration._

import akka.cluster.Cluster
import util.SingleLoggingReceive

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
    implicit val singleMessageFormat = new Format[SingleMessage] {
      def writes(singleMessage: SingleMessage): JsValue = {
        implicit val messageWriteMode = ChatMessageWithCreationDate.JsonConversionMode.Web
        Json.obj(
          "type" -> "message",
          "msg" -> singleMessage.msg
        )
      }
      def reads(json: JsValue) = {
        if ((json \ "type").as[String] != "message") {
          JsError()
        } else {
          val msg = (json \ "msg").as[JsObject]
          implicit val mode = ChatMessageWithCreationDate.JsonConversionMode.Web
          msg.validate[ChatMessageWithCreationDate] match {
            case JsSuccess(c : ChatMessageWithCreationDate, _) =>
              JsSuccess(SingleMessage(c))
            case _ =>
              JsError()
          }
        }
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
        implicit val messageWriteMode = ChatMessageWithCreationDate.JsonConversionMode.Web
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

  case object IsDbUp
  case class IsDbUpTimeout(attemptsCount: Int)
  case object DbIsUp
  case object DbIsNotUpYet
  case class WakeUp(p: scala.concurrent.Promise[Int])
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
      dbService ! IsDbUp
      scheduledTimeout = Some(
        context.system.scheduler.scheduleOnce(3 seconds, self, IsDbUpTimeout(0)))
      context become waitingForDbService
  }

  def waitingForDbService = SingleLoggingReceive {
    case DbIsUp =>
      dbService ! GetTopics
      cancelTimeout()
      scheduledTimeout = Some(context.system.scheduler.scheduleOnce(3 seconds, self, InitialTopicsTimeout))
      context become waitingForInitialTopics
    case DbIsNotUpYet =>
      // do nothing, wait for the timeout
    case IsDbUpTimeout(attemptsCount) if attemptsCount > 3 =>
      out ! Json.obj("type" -> "init error")
    case IsDbUpTimeout(attemptsCount) =>
      dbService ! IsDbUp
      scheduledTimeout = Some(context.system.scheduler.scheduleOnce(
          3 seconds, self, IsDbUpTimeout(attemptsCount + 1)))
  }

  val waitingForInitialTopics = SingleLoggingReceive {
    case t : TopicsListMessage =>
      cancelTimeout()
      mediator ! Subscribe(topicsTopic, self)
      out ! Json.toJson(t)
      context become basic
    case NoTopicsFound =>
      cancelTimeout()
      mediator ! Subscribe(topicsTopic, self)
      out ! Json.obj("type" -> "no topics found")
      context become basic
    case InitialTopicsTimeout =>
      mediator ! Subscribe(topicsTopic, self)
      out ! Json.obj("type" -> "initial topics timeout")
      context become basic
  } orElse basic

  def waitingForMessages = SingleLoggingReceive {
    case c : ChatMessagesListMessage if lastQuery == Some(c.query) =>
      cancelTimeout()
      sleep(3 seconds) onComplete { case _ =>
        out ! Json.toJson(c)
        context become basic
      }
    case NoMessagesFound(q) if lastQuery == Some(q) =>
      cancelTimeout()
      sleep(10 seconds) onComplete { case _ =>
        out ! Json.obj("type" -> "no messages found")
        context become basic
      }
    case MessagesPagerTimeout(q) if lastQuery == Some(q) =>
      out ! Json.obj("type" -> "messages pager timeout")
      context become basic
  } orElse basic

  def basic: Actor.Receive = SingleLoggingReceive {
    case JsString(topicName) => mediator ! Publish(topicsTopic, TopicNameMessage(topicName))
    case js: JsValue =>
      ((js \ "type").as[String]) match {
        case "subscribe" =>
          val topic = (js \ "topic").as[String]
          if (topic != null) {
            unsubscribe()
            topicToSubscribe = Some(topic)
            subscribeToTopic()
            val query = PagerQuery.initial(topic)
            lastQuery = Some(query)
            dbService ! query
            cancelTimeout()
            scheduledTimeout = Some(context.system.scheduler.scheduleOnce(3 seconds, self, MessagesPagerTimeout(query)))
            context become waitingForMessages
          }
        case "message" =>
          js.validate[UserSocket.Message]
            .map(message => (message.topic, Utility.escape(message.msg)))
            .foreach { case (topic, msg) => 
              val chatMessage = ChatMessage(topic, uid, msg).createdNow
              mediator ! Publish(topic, chatMessage)
              mediator ! Publish(messagesTopic, chatMessage)
            }
        case "pager" =>
          js.validate[PagerQuery]
            .foreach { q =>
              dbService ! q
              lastQuery = Some(q)
              cancelTimeout()
              scheduledTimeout = Some(context.system.scheduler.scheduleOnce(3 seconds, self, MessagesPagerTimeout(q)))
              context become waitingForMessages
            }
      }
    case c @ ChatMessageWithCreationDate(ChatMessage(topic, _, _), _) if isSubscribedTo(topic) =>
      implicit val writeMode = ChatMessageWithCreationDate.JsonConversionMode.Web
      out ! Json.toJson(SingleMessage(c))
    case t : TopicNameMessage => out ! Json.toJson(t)

    case WakeUp(p) => p.success(42)
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

  def sleep(d: FiniteDuration) = {
    val p = scala.concurrent.Promise[Int]()
    context.system.scheduler.scheduleOnce(d, self, WakeUp(p))
    p.future
  }
}
