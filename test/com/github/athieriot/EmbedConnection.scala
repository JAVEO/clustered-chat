package com.github.athieriot

import de.flapdoodle.embed.mongo._
import de.flapdoodle.embed.mongo.config._
import de.flapdoodle.embed.mongo.distribution._
import de.flapdoodle.embed.process.runtime.Network
import org.specs2.main.Arguments
import org.specs2.mutable.{Specification, SpecificationLike}
import org.specs2.specification.core.{Fragment, Fragments}

trait EmbedConnection extends Specification {
  self: SpecificationLike =>
  isolated

  def sequentialyIsolated: Arguments = args(isolated = true, sequential = true)

  override def sequential: Arguments = args(isolated = false, sequential = true)

  override def isolated: Arguments = args(isolated = true, sequential = false)

  //Override this method to personalize testing port
  def embedConnectionPort: Int = 12345

  //Override this method to personalize MongoDB version
  def embedMongoDBVersion: Version.Main = Version.Main.PRODUCTION

  lazy val network = new Net(embedConnectionPort, Network.localhostIsIPv6)

  lazy val mongodConfig = new MongodConfigBuilder()
    .version(embedMongoDBVersion)
    .net(network)
    .build

  lazy val runtime = MongodStarter.getDefaultInstance

  lazy val mongodExecutable = runtime.prepare(mongodConfig)

  override def map(fs: => Fragments) = startMongo ^ fs ^ stoptMongo

  private def startMongo = {
    step(mongodExecutable.start)
  }

  private def stoptMongo = {
    step(mongodExecutable.stop())
  }
}
