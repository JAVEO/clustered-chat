package actors

import actors.UserSocket.{Message, SingleMessage}
import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{SubscribeAck, Subscribe}

import play.modules.reactivemongo.ReactiveMongoApi
import com.themillhousegroup.reactivemongo.mocks.MongoMocks

import akka.testkit.TestProbe
import org.specs2.mutable._
import org.specs2.specification.Scope
import play.api.libs.json._
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers.running
import play.api.test.WithApplication

import scala.concurrent.duration._

class UserSocketSpec extends Specification with MongoMocks {
  val UserId = "user1"
  val conf = utilit.Conf.get

  sequential

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

  var portNum = 2552;

  def app = {
    portNum += 1
    val mockapi = mock[play.modules.reactivemongo.ReactiveMongoApi]
    import scala.concurrent.ExecutionContext.Implicits.global
    mockapi.database returns scala.concurrent.Future{mockDB}

    new GuiceApplicationBuilder()
      .in(new java.io.File("conf/application.conf"))
      .configure("akka.remote.netty.tcp.port" -> portNum)
      .in(play.api.Mode.Test)
      .disable[play.modules.reactivemongo.ReactiveMongoModule]
      .overrides(
        bind[ReactiveMongoApi].toInstance(mockapi))
      .build
  }

  "A user socket" should {
    val topic = "chat"

    "send chat message to all subscribers" in new AkkaTestkitSpecs2Support {

      running(app) {

        val mediator = DistributedPubSub(system).mediator

        val browser = TestProbe()
        val chatMember1 = TestProbe()
        val chatMember2 = TestProbe()
        mediator ! Subscribe(topic, chatMember1.ref)
        mediator ! Subscribe(topic, chatMember2.ref)
        val socket = system.actorOf(UserSocket.props("user1", conf)(browser.ref), "userSocket")
        
        val message = "hello"

        chatMember1.expectNoMsg(3 seconds)
        //socket ! Json.obj("type" -> "subscribe", "topic" -> topic)
        //chatMember1.expectNoMsg(1 seconds)
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
    }

    "forward chat message to browser" in new AkkaTestkitSpecs2Support {
      running(app) {
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
        browser.expectMsgPF(10 seconds) {
          case js : JsObject => js.validate[SingleMessage] match {
            case JsSuccess(SingleMessage(ChatMessageWithCreationDate(ChatMessage(t, uid, msg), _)), _) if t == topic && uid == UserId && msg == text =>
              true
          }
        }

      }
    }

  }
}
