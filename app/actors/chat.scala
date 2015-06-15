package actors

import javax.inject.Singleton

import akka.actor._
import akka.cluster.{Member, Cluster}
import akka.cluster.ClusterEvent.InitialStateAsEvents
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.ClusterEvent.MemberRemoved
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.ClusterEvent.UnreachableMember
import akka.event.LoggingReceive
import controllers.Conf
import play.api.libs.json.{JsValue, Json, Writes}
import play.twirl.api.HtmlFormat

@Singleton
class ChatRoomActor extends Actor with ActorLogging {
  val cluster = Cluster(context.system)

  var users = Set[ActorRef]()

  override def preStart(): Unit = {
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])
  }

  def receive = LoggingReceive {
    case Subscribe =>
      users += sender
      context watch sender

    case Terminated(user) =>
      users -= user

    case m: ChatMessage =>
      users foreach { _ ! m }
      forwardToOtherClusterMembers(m)

    case MemberUp(member) =>
      log.info(s"Member is Up: ${member.address}")

    case UnreachableMember(member) =>
      log.info(s"Member detected as unreachable: $member")

    case MemberRemoved(member, previousStatus) =>
      log.info(s"Member is Removed: ${member.address} after $previousStatus")

    case _: MemberEvent => // ignore
  }

  private[actors] def forwardToOtherClusterMembers(chatMessage: ChatMessage) = {
    def forwardToOtherClusterMembers(member: Member) =
      context.actorSelection(RootActorPath(member.address) / "user" / "chat-room") ! chatMessage

    if (isLocal(sender())) {
      otherClusterMembers(Conf.clusterHostname, Conf.clusterPort) foreach forwardToOtherClusterMembers
    }
  }

  private[actors] def isLocal(actor: ActorRef) = actor.path.address.toString == context.system.toString

  private[actors] def otherClusterMembers(hostname: String, port: Int) = {
    def sameHostname(member: Member): Boolean = {
      member.address.host.getOrElse("127.0.0.1") == hostname
    }
    def samePort(member: Member): Boolean = {
      member.address.port.getOrElse(0) == port
    }

    cluster.state.members.filterNot(member => sameHostname(member) && samePort(member))
  }
}

case class ChatMessage(user: String, text: String)

object ChatMessage {
  implicit val chatMessageWrites = new Writes[ChatMessage] {
    def writes(chatMessage: ChatMessage): JsValue = {
      Json.obj(
        "type" -> "message",
        "user" -> chatMessage.user,
        "text" -> multiLine(chatMessage.text)
      )
    }
  }

  private def multiLine(text: String) = {
    HtmlFormat.raw(text).body.replace("\n", "<br/>")
  }
}
object Subscribe
