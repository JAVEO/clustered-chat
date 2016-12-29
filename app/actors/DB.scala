package actors

import actors.UserSocket.Message
import actors.UserSocket.Message.messageReads
import actors.ChatMessageWithCreationDate._
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Publish, Subscribe, Unsubscribe}
import akka.event.LoggingReceive
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.twirl.api.HtmlFormat
import play.api.libs.functional.syntax._

import scala.xml.Utility
import scala.concurrent.duration._

import akka.cluster.Cluster

import play.modules.reactivemongo._
import reactivemongo.api.ReadPreference
import reactivemongo.play.json._
import reactivemongo.play.json.collection._
import reactivemongo.bson.{BSONDocument, BSONDateTime }
import extensions.SystemScoped
import akka.actor.{ ActorSystem, Props, ActorRef, Extension, ExtensionId, ExtensionIdProvider, ExtendedActorSystem }
import play.api.Play

object DBService extends SystemScoped {
  override lazy val instanceProps = Props[DBServiceImpl]
  override lazy val instanceName = "db-service-actor"
}

object DBServiceMessages {
  case object GetTopics {}
  case class GetOldMessages(topic: String, olderThan: java.util.Date, limit: Int)
}

case class Topic(name: String)

object Topic {
  implicit val topicFormat = Json.format[Topic]
}

class DBServiceImpl extends Actor with ActorLogging {
  import DBServiceMessages._
  import actors.UserSocket._
  import scala.concurrent.ExecutionContext.Implicits.global

  import actors.ChatMessageWithCreationDate.{chatMessageReads, chatMessageWrites}

  val reactiveMongoApi = Play.current.injector.instanceOf[ReactiveMongoApi]
  val conf = Play.current.injector.instanceOf[play.api.Configuration]

  def coll(name: String) = reactiveMongoApi.database.map(_.collection[JSONCollection](name))

  def buildQuery(msg: GetOldMessages) : BSONDocument = {
    val GetOldMessages(topic, date, _) = msg
    return BSONDocument(
      "topic" -> topic,
      "creationDate" -> BSONDocument(
        "$lte" -> BSONDateTime(date.getTime)
        )
      )
  }

  val mediator = DistributedPubSub(context.system).mediator

  val topicsTopic = conf.getString("my.special.string") + "topics"
  val messagesTopic = conf.getString("my.special.string") + "messages"
  mediator ! Subscribe(topicsTopic, self)
  mediator ! Subscribe(messagesTopic, self)

  def receive = LoggingReceive {
    case c @ ChatMessage(topicName, _, _) => 
      for {
        messagesColl <- coll("messages")
        result <- messagesColl.insert(c.createdNow)
      } {
        // do nothing
      }
    case TopicNameMessage(topicName) => 
      for {
        topicsColl <- coll("topics")
        result <- topicsColl.insert(Topic(topicName))
      } {
        // do nothing
      }
    case GetTopics => 
      val sndr = sender
      for {
        topicsColl <- coll("topics")
        topics <- topicsColl.find(Json.obj()).cursor[Topic]().collect[List]()
      } {
        sndr ! TopicsListMessage(topics.map(_.name))
      }
    case msg @ GetOldMessages(_, _, limit) =>
      val sndr = sender
      for {
        messagesColl <- coll("messages")
        messages <- messagesColl
          .find(buildQuery(msg))
          .cursor[ChatMessageWithCreationDate]()
          .collect[List](limit)
      } {
        sndr ! ChatMessagesListMessage(messages)
      }
  }
}
