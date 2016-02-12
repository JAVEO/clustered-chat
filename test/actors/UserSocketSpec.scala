package actors

import actors.UserSocket.Message
import actors.ChatMessage
import akka.actor._
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.LWWRegister
import akka.cluster.ddata.Replicator
import akka.cluster.ddata.Replicator._
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


      //val mediator = DistributedPubSub(system).mediator
      val replicator = DistributedData(system).replicator
      val browser = TestProbe()
      val chatMember1 = TestProbe()
      val chatMember2 = TestProbe()
      replicator ! Subscribe(UserSocket.topicMsgKey(topic), chatMember1.ref)
      replicator ! Subscribe(UserSocket.topicMsgKey(topic), chatMember2.ref)
      val socket = system.actorOf(UserSocket.props("user1")(browser.ref), "userSocket")
      
      browser.expectNoMsg(6 seconds)

      val message = "hello"

      socket ! Json.toJson(Message(topic, message))

      chatMember1.expectMsgPF(3 seconds){case c : Changed[LWWRegister[ChatMessage]] => true}
      chatMember2.expectMsgPF(3 seconds){case c : Changed[LWWRegister[ChatMessage]] => true}
    }

    "forward chat message to browser" in new AkkaTestkitSpecs2Support {
      val replicator = DistributedData(system).replicator
      val browser = TestProbe()
      val socket = system.actorOf(UserSocket.props(UserId)(browser.ref), "userSocket")
      val text = "There is important thing to do!"
      val chatMessage = ChatMessage(topic, UserId, text, new java.util.Date())

      browser.expectNoMsg(6 seconds)
      socket ! Json.toJson(MsgSubscribe(topic))
      browser.expectNoMsg(2 seconds)
      socket ! Json.toJson(Message(topic, text))

      browser.expectMsg(Json.toJson(chatMessage))
    }
  }
}
