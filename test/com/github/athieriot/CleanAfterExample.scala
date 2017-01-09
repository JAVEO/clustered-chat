package com.github.athieriot

import org.specs2.specification.AfterEach
import reactivemongo.api.MongoDriver

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

import com.github.athieriot.util.ListDatabases

trait CleanAfterExample extends AfterEach {
  self: EmbedConnection =>

  private def getConn = {
    val driver = new MongoDriver
    val address = "127.0.0.1:" + network.getPort
    val conn = driver.connection(address :: Nil)
    conn
  }

  def after = {
    val conn = getConn
    val futureDropResults = for {
      someDb <- conn.database("some_db")
      dbNames <- someDb.command(ListDatabases)
      dbs <- Future.traverse(dbNames)(conn.database(_))
      drops <- Future.traverse(dbs)(_.drop())
    } yield {
      drops
    }
    futureDropResults onFailure { case _ =>
      throw new RuntimeException("couldn't drop all DBs")
    }
    Await.ready(futureDropResults, 1 second)
  }

}
