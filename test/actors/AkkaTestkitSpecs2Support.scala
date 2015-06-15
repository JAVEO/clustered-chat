package actors

import akka.actor._
import akka.testkit._
import org.specs2.mutable._

abstract class AkkaTestkitSpecs2Support extends TestKit(ActorSystem())
  with After
  with ImplicitSender {

  def after = system.shutdown()
}
