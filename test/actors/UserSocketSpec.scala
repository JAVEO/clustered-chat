package actors

import actors.UserSocket.Message
import actors.ChatMessage
import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{SubscribeAck, Subscribe}

import akka.testkit.TestProbe
import org.specs2.mutable._
import play.api.libs.json._

import scala.concurrent.duration._

class UserSocketSpec extends Specification {
  val UserId = "user1"

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

  "A user socket" should {
    val topic = "chat"

    "send chat message to all subscribers" in new AkkaTestkitSpecs2Support {


      val mediator = DistributedPubSub(system).mediator

      val browser = TestProbe()
      val chatMember1 = TestProbe()
      val chatMember2 = TestProbe()
      mediator ! Subscribe(topic, chatMember1.ref)
      mediator ! Subscribe(topic, chatMember2.ref)
      val socket = system.actorOf(UserSocket.props("user1")(browser.ref), "userSocket")
      
      val message = "hello"

      socket ! Json.toJson(Message(topic, message))

      chatMember1.ignoreMsg({case SubscribeAck => true})
      chatMember1.expectMsg(ChatMessage(topic, UserId, message))
      chatMember2.ignoreMsg({case SubscribeAck => true})
      chatMember2.expectMsg(ChatMessage(topic, UserId, message))
    }

    "forward chat message to browser" in new AkkaTestkitSpecs2Support {
      val browser = TestProbe()
      val socket = system.actorOf(UserSocket.props(UserId)(browser.ref), "userSocket")
      val text = "There is important thing to do!"
      val chatMessage = ChatMessage(topic, UserId, text)

      browser.expectNoMsg(6 seconds)
      socket ! Json.toJson(MsgSubscribe(topic))
      browser.expectNoMsg(2 seconds)
      socket ! Json.toJson(Message(topic, text))

      browser.expectMsg(Json.toJson(chatMessage))
    }
  }
}
