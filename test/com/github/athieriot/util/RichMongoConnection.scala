package com.github.athieriot.util

import scala.concurrent.{ ExecutionContext, Future }
import reactivemongo.api.{ BSONSerializationPack, GenericDB, MongoConnection }

class RichMongoConnection(conn: MongoConnection) {
  def listDatabases()(implicit ec: ExecutionContext): Future[List[String]] =
    CustomCommandRunner.listDatabases(conn)
}

object RichMongoConnection {
  implicit def conn2richConn(conn: MongoConnection) = new RichMongoConnection(conn)
}
