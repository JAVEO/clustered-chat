// see
// https://anandparthasarathy.wordpress.com/2016/01/21/singleton-instance-of-akka-actor-per-actor-system/
// http://doc.akka.io/docs/akka/current/scala/extending-akka.html

package extensions

import akka.actor.{ ActorSystem, Props, ActorRef, Extension, ExtensionId, ExtensionIdProvider, ExtendedActorSystem }

class SystemScopedImpl(system: ActorSystem, props: Props, name: String) extends Extension {
  val instance: ActorRef = system.actorOf(props, name = name)
}

trait SystemScoped extends ExtensionId[SystemScopedImpl] with ExtensionIdProvider {
  final override def lookup = this
  final override def createExtension(system: ExtendedActorSystem) = new SystemScopedImpl(system, instanceProps, instanceName)

  protected def instanceProps: Props
  protected def instanceName: String
}
