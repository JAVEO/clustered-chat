package actors

import actors.UserSocket.{Message, SingleMessage, TopicNameMessage, ChatMessagesListMessage}
import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Publish, SubscribeAck, Subscribe}

import com.github.athieriot.{EmbedConnection, CleanAfterExample}

import reactivemongo.bson.{
    BSONDocumentWriter, BSONDocumentReader, Macros, document
}

import akka.testkit.TestProbe
import org.specs2.mutable._
import play.api.libs.json._

import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }

object Util {
  implicit val messageWrites = new Writes[Message] {
    def writes(message: Message) = Json.obj(
      "type" -> "message",
      "topic" -> message.topic,
      "msg" -> message.msg
    )
  }
  case class MsgSubscribe(topic: String)
  implicit val msgSubscribeWrites = new Writes[MsgSubscribe] {
    def writes(message: MsgSubscribe) = Json.obj(
      "type" -> "subscribe",
      "topic" -> message.topic
    )
  }
}

class UserSocketSpec extends Specification
    with EmbedConnection
    with CleanAfterExample {
  import Util._

  val UserId = "user1"

  sequential

  "A user socket" should {
    val topic = "chat"

    "when there is no data: send \"no topics found\" at start" in new AkkaSpecsWithApp {
        val browser = TestProbe()
        val socket = system.actorOf(UserSocket.props(UserId, conf)(browser.ref), "userSocket")

        browser.expectMsgPF(10 seconds) {
          case msg if msg == Json.obj("type" -> "no topics found") =>
            true
        }

    }
    "when there is no data: send \"no messages found\" when subscribed" in new AkkaSpecsWithApp {
        val browser = TestProbe()
        val socket = system.actorOf(UserSocket.props(UserId, conf)(browser.ref), "userSocket")

        browser.expectMsgPF(10 seconds) {
          case msg if msg == Json.obj("type" -> "no topics found") =>
            true
        }
        socket ! Json.toJson(MsgSubscribe(topic))
        browser.expectMsgPF(11 seconds) {
          case msg if msg == Json.obj("type" -> "no messages found") =>
            true
        }

    }
    "send chat message to all subscribers" in new AkkaSpecsWithApp {

        val mediator = DistributedPubSub(system).mediator

        val browser = TestProbe()
        val chatMember1 = TestProbe()
        val chatMember2 = TestProbe()
        mediator ! Subscribe(topic, chatMember1.ref)
        mediator ! Subscribe(topic, chatMember2.ref)
        val socket = system.actorOf(UserSocket.props("user1", conf)(browser.ref), "userSocket")
        
        val message = "hello"

        chatMember1.expectNoMsg(15 seconds)
        socket ! Json.toJson(Message(topic, message))

        chatMember1.ignoreMsg({case SubscribeAck => true})
        chatMember1.expectMsgPF() {
          case ChatMessageWithCreationDate(ChatMessage(t, uid, msg), _) if t == topic && uid == UserId && msg == message =>
            true
        }
        chatMember2.ignoreMsg({case SubscribeAck => true})
        chatMember2.expectMsgPF() {
          case ChatMessageWithCreationDate(ChatMessage(t, uid, msg), _) if t == topic && uid == UserId && msg == message =>
            true
        }
    }

    "escape message text before sending to browser" in new AkkaSpecsWithApp {
        val browser = TestProbe()
        val socket = system.actorOf(UserSocket.props(UserId, conf)(browser.ref), "userSocket")
        val text = "There <is> 'important' \"thing\" to & do!\n"
        val escapedText = "There &lt;is&gt; &#x27;important&#x27; &quot;thing&quot; to &amp; do!<br/>"
        val chatMessage = ChatMessage(topic, UserId, text)

        browser.expectMsgPF(10 seconds) {
          case msg if msg == Json.obj("type" -> "no topics found") =>
            true
        }
        socket ! Json.toJson(MsgSubscribe(topic))
        browser.expectMsgPF(11 seconds) {
          case msg if msg == Json.obj("type" -> "no messages found") =>
            true
        }
        socket ! Json.toJson(Message(topic, text))
        implicit val msgJsonConversionMode = ChatMessageWithCreationDate.JsonConversionMode.WebWithoutUnescaping

        def containsEscapedText(js: JsObject) = js.validate[SingleMessage] match {
          case JsSuccess(SingleMessage(ChatMessageWithCreationDate(ChatMessage(t, uid, msg), _)), _) if t == topic && uid == UserId && msg == escapedText =>
            true
          case _ =>
            false
        }
        browser.expectMsgPF(10 seconds) {
          case js : JsObject if containsEscapedText(js) =>
            true
        }

    }

    "forward chat message to browser" in new AkkaSpecsWithApp {
        val browser = TestProbe()
        val socket = system.actorOf(UserSocket.props(UserId, conf)(browser.ref), "userSocket")
        val text = "There is important thing to do!"
        val chatMessage = ChatMessage(topic, UserId, text)

        browser.expectMsgPF(10 seconds) {
          case msg if msg == Json.obj("type" -> "no topics found") =>
            true
        }
        socket ! Json.toJson(MsgSubscribe(topic))
        browser.expectMsgPF(11 seconds) {
          case msg if msg == Json.obj("type" -> "no messages found") =>
            true
        }
        socket ! Json.toJson(Message(topic, text))
        implicit val msgJsonConversionMode = ChatMessageWithCreationDate.JsonConversionMode.Web
        browser.expectMsgPF(10 seconds) {
          case js : JsObject => js.validate[SingleMessage] match {
            case JsSuccess(SingleMessage(ChatMessageWithCreationDate(ChatMessage(t, uid, msg), _)), _) if t == topic && uid == UserId && msg == text =>
              true
          }
        }

    }

    "send new topic to all users" in new AkkaSpecsWithApp {

        val mediator = DistributedPubSub(system).mediator

        val browser = TestProbe()
        val chatMember1 = TestProbe()
        val chatMember2 = TestProbe()
        val topicsTopic = conf.getString("my.special.string") + "topics"
        mediator ! Subscribe(topicsTopic, chatMember1.ref)
        mediator ! Subscribe(topicsTopic, chatMember2.ref)
        val socket = system.actorOf(UserSocket.props("user1", conf)(browser.ref), "userSocket")
        
        val newTopicName = "newtopic"

        browser.expectMsgPF(10 seconds) {
          case msg if msg == Json.obj("type" -> "no topics found") =>
            true
        }
        //chatMember1.expectNoMsg(3 seconds)
        socket ! play.api.libs.json.JsString(newTopicName)

        val topicNamePF: PartialFunction[Any, Boolean] = {
          case TopicNameMessage(t) if t == newTopicName => true
        }
        chatMember1.ignoreMsg({case SubscribeAck => true})
        chatMember1.expectMsgPF()(topicNamePF)
        chatMember2.ignoreMsg({case SubscribeAck => true})
        chatMember2.expectMsgPF()(topicNamePF)
    }

    val topics = List[String](topic, "first", "topic 2", "third topic")

    val topicsAsJsObjects: List[JsObject] = topics.map { topic: String =>
      Json.obj("name" -> topic)
    }


    val initialMessages = List(
      ChatMessage(topic, "john", "hi"),
      ChatMessage(topic, "mike", "this is a message"),
      ChatMessage(topic, "kp7483", "nsdv83474")
    ).map(_.createdNow)

    implicit val mode = ChatMessageWithCreationDate.JsonConversionMode.Mongo
    
    val messagesAsJsObjects: List[JsObject] = initialMessages.map(Json.toJson(_).as[JsObject])

    "ignore a message with a wrong topic" in new AkkaSpecsWithApp {
      
        def buildPartialFunc(uid: String, text: String): PartialFunction[Any, Boolean] = {
          case SingleMessage(ChatMessageWithCreationDate(ChatMessage(t, uid, msg), _)) if t == topic && uid == uid && msg == text =>
            true
        }
        
        val mediator = DistributedPubSub(system).mediator

        val browser = TestProbe()
        val socket = system.actorOf(UserSocket.props("user1", conf)(browser.ref), "userSocket")

        browser.expectMsgPF(10 seconds) {
          case msg if msg == Json.obj("type" -> "no topics found") =>
            true
        }
        socket ! Json.toJson(MsgSubscribe(topic))

        browser.expectMsgPF(11 seconds) {
          case msg if msg == Json.obj("type" -> "no messages found") =>
            true
        }

        val uid1 = "john"
        val text1 = "hello"

        val uid2 = "mick"
        val text2 = "this is a message"

        val wrongTopic = topics((topics.indexOf(topic) + 1) % topics.length)
        mediator ! Publish(wrongTopic, ChatMessage(wrongTopic, uid1, text1).createdNow)

        browser.expectNoMsg(10 seconds)
        mediator ! Publish(wrongTopic, ChatMessage(wrongTopic, uid2, text2).createdNow)
        browser.expectNoMsg(10 seconds)

    }
    "when there is data: send initial messages when subscribed" in new AkkaSpecsWithData("messages" -> messagesAsJsObjects) {
        val browser = TestProbe()
        val socket = system.actorOf(UserSocket.props(UserId, conf)(browser.ref), "userSocket")

        browser.expectMsgPF(10 seconds) {
          case msg if msg == Json.obj("type" -> "no topics found") =>
            true
        }
        socket ! Json.toJson(MsgSubscribe(topic))
        implicit val mode = ChatMessageWithCreationDate.JsonConversionMode.Web
        def isValidJs(jsval: JsValue) = jsval.validate[ChatMessagesListMessage] match {
          case JsSuccess(ChatMessagesListMessage(_, _, msgs), _) if msgs.toSet == initialMessages.toSet =>
            true
        }
        browser.expectMsgPF(12 seconds) {
          case jsval: JsValue if isValidJs(jsval) =>
            true
        }

    }
    "send new messages while waiting for initial ones" in new AkkaSpecsWithData("topics" -> topicsAsJsObjects) {
        
        implicit val mode = ChatMessageWithCreationDate.JsonConversionMode.Web

        def isValidJs(js: JsObject, uid: String, text: String) = js.validate[SingleMessage] match {
          case JsSuccess(SingleMessage(ChatMessageWithCreationDate(ChatMessage(t, uid, msg), _)), _) if t == topic && uid == uid && msg == text =>
            true
          case _ => false
        }

        
        def buildPartialFunc(uid: String, text: String): PartialFunction[Any, Boolean] = {
          case js : JsObject if isValidJs(js, uid, text) => true
        }

        val mediator = DistributedPubSub(system).mediator

        val browser = TestProbe()
        val socket = system.actorOf(UserSocket.props("user1", conf)(browser.ref), "userSocket")

        val expectedData = Json.toJson(UserSocket.TopicsListMessage(topics))

        browser.expectMsgPF(10 seconds) {
          case msg if msg == expectedData => true
        }
        socket ! Json.toJson(MsgSubscribe(topic))
        browser.expectNoMsg(1 seconds)

        val uid1 = "john"
        val text1 = "hello"

        val uid2 = "mick"
        val text2 = "this is a message"

        mediator ! Publish(topic, ChatMessage(topic, uid1, text1).createdNow)

        browser.expectMsgPF(10 seconds)(buildPartialFunc(uid1, text1))

        mediator ! Publish(topic, ChatMessage(topic, uid2, text2).createdNow)

        browser.expectMsgPF(10 seconds)(buildPartialFunc(uid2, text2))

    }
    "when there is data: send initial topics at start" in new AkkaSpecsWithData("topics" -> topicsAsJsObjects) {

        val browser = TestProbe()
        val socket = system.actorOf(UserSocket.props(UserId, conf)(browser.ref), "userSocket")

        val expectedData = Json.toJson(UserSocket.TopicsListMessage(topics))

        browser.expectMsgPF(10 seconds) {
          case msg if msg == expectedData => true
        }

    }
  }
}

class UserSocketWithoutWorkingDbSpec
    extends Specification {
  import Util._

  val UserId = "user1"

  sequential

  "When DB is not working a user socket" should {
    val topic = "chat"
    "send the error message" in new AkkaSpecsWithApp {
      val browser = TestProbe()
      val socket = system.actorOf(UserSocket.props(UserId, conf)(browser.ref), "userSocket")

      browser.expectMsgPF(15 seconds) {
        case msg if msg == Json.obj("type" -> "init error") =>
          true
      }
    }
  }
}
