package actors

import actors.UserSocket._
import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Publish, SubscribeAck, Subscribe}

import reactivemongo.bson.{
    BSONDocumentWriter, BSONDocumentReader, Macros, document
}
import reactivemongo.api.{ DefaultDB, MongoConnection, MongoDriver }
import play.modules.reactivemongo.json._
import play.modules.reactivemongo.json.collection._

import com.github.athieriot.{EmbedConnection, CleanAfterExample}

import org.specs2.mutable._
import org.specs2.matcher.StandardMatchResults
import play.api.libs.json._
import play.api.Application

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise, ExecutionContext }

import utilit.MongoMocksWithAppBuilders

object DBServiceSpec {

  val UserId = "user1"

  val topic = "chat"

  val topics = List[String](topic, "first", "topic 2", "third topic")

  val topicsAsJsObjects: List[JsObject] = topics.map { topic: String =>
    Json.obj("name" -> topic)
  }

  implicit val mode = ChatMessageWithCreationDate.JsonConversionMode.Mongo

  val initialMessages = List(
      ChatMessage(topic, "john", "hi"),
      ChatMessage(topic, "mike", "this is a message"),
      ChatMessage(topic, "kp7483", "nsdv83474")
    ).zipWithIndex.map { case (msg, ind) =>
      msg.withCreationDate(new java.util.Date(1000 * ind))
    }
  
  val messagesAsJsObjects: List[JsObject] = initialMessages.map(Json.toJson(_).as[JsObject])

  val lotsOfMessages: Array[ChatMessageWithCreationDate] = {
    val lst = List(
      ChatMessage(topic, "jim", "Hello"),
      ChatMessage(topic, "sara", "This message is from sara"),
      ChatMessage(topic, "alex", "This message is from alex")
    )
    // repeat lst 5 times, concatenating number to the text
    val fifteenMsgs = (1 to 5).foldRight(List.empty[ChatMessage]) {
      (n: Int, acc: List[ChatMessage]) =>
        acc ::: lst.map(msg => msg.copy(text = msg.text + n))
    }
    fifteenMsgs.zipWithIndex.map { case (msg, ind) =>
      msg.withCreationDate(new java.util.Date(1000 * ind))
    }.toArray
  }
  
  val lotsOfMessagesAsJsObjects: List[JsObject] = lotsOfMessages.toList.map(Json.toJson(_).as[JsObject])


  trait DbAware { this: AkkaSpecsWithApp =>

    def waitForDb(dbService: ActorRef)(implicit sys: ActorSystem) {
      import scala.concurrent.ExecutionContext.Implicits.global
      dbService ! IsDbUp
      fishForMessage(15 seconds) {
        case DbIsNotUpYet =>
          system.scheduler.scheduleOnce(1 seconds, dbService, IsDbUp)
          false
        case DbIsUp => true
        case _ => false
      }
    }

  }

  abstract class DbAwareAkkaSpecsWithApp(
      app: Application = AkkaSpecsWithApp.defaultApp
    ) extends AkkaSpecsWithApp(app) with DbAware

  abstract class DbAwareAkkaSpecsWithData(
      dataToInsert: Map[String, Seq[JsObject]],
      app: Application = AkkaSpecsWithApp.defaultApp,
      durationToWaitForInserting: FiniteDuration = 5 seconds
    ) extends AkkaSpecsWithData(dataToInsert, app, durationToWaitForInserting) with DbAware {

    def this(couples: (String, Seq[JsObject])*) = this(couples.toMap)
    def this(app: Application, couples: (String, Seq[JsObject])*) = this(couples.toMap, app)
  }

  trait EmbeddedMongoAware {

    def defaultDB: Future[DefaultDB] = {

      import scala.concurrent.ExecutionContext.Implicits.global

      val mongouri = utilit.Conf.get.getString("mongodb.uri").get
      val driver = MongoDriver()
      val futureUri = Future.fromTry(MongoConnection.parseURI(mongouri))
      val futureConnection = futureUri.map(driver.connection(_))
      for {
        conn <- futureConnection
        uri <- futureUri
        dn = uri.db.get
        db <- conn.database(dn)
      } yield db
    }

  }

}

class DBServiceWithMockedMongoSpec extends Specification
    with MongoMocksWithAppBuilders {

  import DBServiceSpec._

  sequential

  "DBService" should {
    "in response to IsDbUp: " +
    "send DbIsNotUpYet while waiting for db to initialize" in new AkkaSpecsWithApp(delayedApp(10 seconds)) {
      val dbService = DBService(system).instance
      dbService ! IsDbUp
      expectMsg(DbIsNotUpYet)
    }
    "in response to IsDbUp: send DbIsUp when db is ready" in new AkkaSpecsWithApp(delayedApp(10 seconds)) {
      val dbService = DBService(system).instance
      expectNoMsg(12 seconds)
      dbService ! IsDbUp
      expectMsg(DbIsUp)
    }
    "when there is no data: send \"topics not found\" message" in new DbAwareAkkaSpecsWithApp(app) {
      val dbService = DBService(system).instance
      waitForDb(dbService)
      dbService ! GetTopics
      expectMsg(NoTopicsFound)
    }
    "when there is no data: send \"messages not found\" message" in new DbAwareAkkaSpecsWithApp(app) {
      val dbService = DBService(system).instance
      waitForDb(dbService)
      val query = PagerQuery.initial(topic)
      dbService ! query
      expectMsg(NoMessagesFound(query))
    }
  }
}

class DBServiceWithEmbeddedMongoSpec extends Specification
    with EmbedConnection
    with CleanAfterExample {

  import DBServiceSpec._

  sequential

  "DBService when there is data" should {

    "send initial topics list" in new DbAwareAkkaSpecsWithData("topics" -> topicsAsJsObjects) {
      val dbService = DBService(system).instance
      waitForDb(dbService)
      dbService ! GetTopics
      expectMsg(TopicsListMessage(topics))
    }
    "send messages list" in new DbAwareAkkaSpecsWithData("messages" -> messagesAsJsObjects) {
      val dbService = DBService(system).instance
      waitForDb(dbService)
      val query = PagerQuery.initial(topic)
      dbService ! query
      expectMsg(ChatMessagesListMessage(query, true, initialMessages))
    }
    "in response to pager query: " +
    "send older messages" in new DbAwareAkkaSpecsWithData("messages" -> lotsOfMessagesAsJsObjects) {

      val dbService = DBService(system).instance
      waitForDb(dbService)

      for ((msg, ind) <-  lotsOfMessages.zipWithIndex) {
        val ChatMessageWithCreationDate(_, date) = msg

        val queryOlder = PagerQuery(topic, PagerQuery.Direction.Older, date.getTime)
        dbService ! queryOlder
        val answer = {
          val isLast = (ind - 10 <= 0)
          val expectedMessages = lotsOfMessages.slice(ind - 10, ind + 1)
          ChatMessagesListMessage(queryOlder, isLast, expectedMessages)
        }
        expectMsg(answer)

      }
    }
    "in response to pager query: " +
    "send newer messages" in new DbAwareAkkaSpecsWithData("messages" -> lotsOfMessagesAsJsObjects) {

      val dbService = DBService(system).instance
      waitForDb(dbService)

      for ((msg, ind) <-  lotsOfMessages.zipWithIndex) {
        val ChatMessageWithCreationDate(_, date) = msg

        val queryNewer = PagerQuery(topic, PagerQuery.Direction.Newer, date.getTime)
        dbService ! queryNewer
        val answer = {
          val isLast = (lotsOfMessages.length <= ind + 11)
          val expectedMessages = lotsOfMessages.slice(ind, ind + 11)
          ChatMessagesListMessage(queryNewer, isLast, expectedMessages)
        }
        expectMsg(answer)

      }
    }
  }

  "DBService" should {

    import scala.concurrent.ExecutionContext.Implicits.global
    import DBServiceUtil.Topic

    "save topic to db" in new DbAwareAkkaSpecsWithApp with EmbeddedMongoAware {
      val dbService = DBService(system).instance
      waitForDb(dbService)
      dbService ! TopicNameMessage(topic)

      val topicsFuture = for {
        db <- defaultDB
        coll = db.collection[JSONCollection]("topics")
        topics <- coll.find(Json.obj()).cursor[Topic].collect[List]()
      } yield topics

      val topicName = topic
      Await.result(topicsFuture, 3 seconds) must be_==(List(Topic(topicName)))
    }
    "save message to db" in new DbAwareAkkaSpecsWithApp with EmbeddedMongoAware {
      val dbService = DBService(system).instance
      waitForDb(dbService)
      val msg = ChatMessage(topic, UserId, "this message should be inserted").createdNow
      dbService ! msg

      val messagesFuture = for {
        db <- defaultDB
        coll = db.collection[JSONCollection]("messages")
        messages <- coll.find(Json.obj()).cursor[ChatMessageWithCreationDate].collect[List]()
      } yield messages

      Await.result(messagesFuture, 3 seconds) must be_==(List(msg))

    }
  }
}
