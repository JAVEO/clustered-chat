package controllers

import play.api.Play
import play.api.Play.current

trait Conf {
  def clusterHostname = Play.application.configuration.getString("akka.remote.netty.tcp.hostname").getOrElse("127.0.0.1")
  def clusterPort = Play.application.configuration.getInt("akka.remote.netty.tcp.port").getOrElse(0)
}

object Conf extends Conf
