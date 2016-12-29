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
import scala.util.{Success, Failure}

object DBService extends SystemScoped {
  override lazy val instanceProps = Props[DBServiceImpl]
  override lazy val instanceName = "db-service-actor"
}

object DBServiceMessages {

  case object GetTopics

  case class GetInitialMessages(topic: String, limit: Int)

  object GetMessages {

    object Direction extends Enumeration {
      type Direction = Value
      val Older, Newer = Value
      def withLowercaseName(name: String) = this.withName(name.capitalize)
    }

    import UserSocket.PagerQuery

    def apply(query : PagerQuery) = 
      new GetMessages(query.topic, Direction.withLowercaseName(query.direction), new java.util.Date(query.date), 10)

    def initial(topic: String, limit: Int) = new GetMessages(topic, limit = limit, direction = Direction.Older, date = new java.util.Date())

  }

  import GetMessages.Direction

  case class GetMessages(topic: String, direction: Direction.Value, date: java.util.Date, limit: Int)

  case object NoMessagesFound
}

case class Topic(name: String)

object Topic {
  implicit val topicFormat = Json.format[Topic]
}

class DBServiceImpl extends Actor with ActorLogging {
  import DBServiceMessages._
  import actors.UserSocket._
  import GetMessages.Direction
  import scala.concurrent.ExecutionContext.Implicits.global

  import actors.ChatMessageWithCreationDate.{chatMessageReads, chatMessageWrites}

  val reactiveMongoApi = Play.current.injector.instanceOf[ReactiveMongoApi]
  val conf = Play.current.injector.instanceOf[play.api.Configuration]

  def coll(name: String) = reactiveMongoApi.database.map(_.collection[JSONCollection](name))

  def buildQuery(topic: String, date: java.util.Date, direction: Direction.Value) : BSONDocument = {
    val op = direction match {
      case Direction.Older =>
        "$lte"
      case Direction.Newer =>
        "$gte"
    }
    return BSONDocument(
      "topic" -> topic,
      "creationDate" -> BSONDocument(
        op -> BSONDateTime(date.getTime)
        )
      )
  }

  def byDate(direction: Direction.Value) = {
    val sorting = direction match {
      case Direction.Older => -1
      case Direction.Newer => 1
    }
    Json.obj(
      "creationDate" -> sorting
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
      val topicsFuture = for {
        topicsColl <- coll("topics")
        topics <- topicsColl.find(Json.obj()).cursor[Topic]().collect[List]()
      } yield topics

      topicsFuture onComplete {
        case Success(topics) =>
          sndr ! TopicsListMessage(topics.map(_.name))
        case Failure(_) =>
          sndr ! TopicsListMessage(List.empty[String])
      }
    case msg @ GetMessages(topic, direction, date, limit) =>
      val sndr = sender
      val msgsFuture = for {
        messagesColl <- coll("messages")
        messages <- messagesColl
          .find(buildQuery(topic, date, direction))
          .sort(byDate(direction))
          .cursor[ChatMessageWithCreationDate]()
          .collect[List](limit)
      } yield messages
      
      msgsFuture onComplete {
        case Success(msgs) =>
          sndr ! ChatMessagesListMessage(msgs.sorted)
        case Failure(_) =>
          sndr ! NoMessagesFound
      }
  }
}
