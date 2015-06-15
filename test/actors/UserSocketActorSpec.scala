package actors

import actors.UserSocketActor.Message
import akka.actor._
import akka.testkit.TestProbe
import org.specs2.mutable._
import play.api.libs.json._

import scala.language.postfixOps

class UserSocketActorSpec extends Specification {
  val UserId = "user1"

  "A user socket actor" should {
    "send Subscribe to room" in new AkkaTestkitSpecs2Support {
      val room = TestProbe()
      val browser = TestProbe()

      val socket = system.actorOf(UserSocketActor.props(room.ref, "user1")(browser.ref), "userSocket")

      room.expectMsg(Subscribe)
    }

    "react on messages from web socket" in new AkkaTestkitSpecs2Support {
      implicit val messageWrites = Json.writes[Message]

      val room = TestProbe()
      room.ignoreMsg({ case Subscribe => true })
      val browser = TestProbe()
      val socket = system.actorOf(UserSocketActor.props(room.ref, UserId)(browser.ref), "userSocket")
      val message = "message from browser"

      socket ! Json.toJson(Message(message))

      room.expectMsg(ChatMessage(UserId, message))
    }

    "forward chat message to browser" in new AkkaTestkitSpecs2Support {
      val room = TestProbe()
      room.ignoreMsg({ case Subscribe => true })
      val browser = TestProbe()
      val socket = system.actorOf(UserSocketActor.props(room.ref, UserId)(browser.ref), "userSocket")
      val chatMessage = ChatMessage(UserId, "There is important thing to do!")

      socket ! chatMessage

      browser.expectMsg(Json.toJson(chatMessage))
    }
  }
}
