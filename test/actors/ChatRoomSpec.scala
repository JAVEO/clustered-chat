package actors

import actors.ChatRoom.{ChatMessage, Subscribe}
import akka.actor.Props
import akka.testkit.{TestActorRef, TestProbe}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification

import scala.language.postfixOps

class ChatRoomSpec extends Specification with Mockito {

  "Chat room" should {

    "add users" in new AkkaTestkitSpecs2Support {
      val firstUser = TestProbe()
      val secondUser = TestProbe()
      val room = TestActorRef[ChatRoom]

      room.tell(Subscribe, firstUser.ref)
      room.tell(Subscribe, secondUser.ref)

      room.underlyingActor.users must containTheSameElementsAs(Seq(firstUser.ref, secondUser.ref))
    }

    "forward chat message to users" in new AkkaTestkitSpecs2Support {
      val firstUser = TestProbe()
      val secondUser = TestProbe()
      val thirdUser = TestProbe()
      val room = system.actorOf(Props[ChatRoom], "chat-room")
      val message = ChatMessage("username", "hello everybody!")

      room.tell(Subscribe, firstUser.ref)
      room.tell(Subscribe, secondUser.ref)
      room.tell(Subscribe, thirdUser.ref)

      room ! message

      firstUser.expectMsg(message)
      secondUser.expectMsg(message)
      thirdUser.expectMsg(message)
    }
  }
}
