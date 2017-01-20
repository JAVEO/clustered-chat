package com.github.athieriot.util

// see http://reactivemongo.org/releases/0.11/documentation/advanced-topics/commands.html

import reactivemongo.api.BSONSerializationPack
import reactivemongo.api.commands.{
  Command,
  CommandWithPack,
  CommandWithResult,
  ImplicitCommandHelpers
}

object ListDatabasesCommand extends ImplicitCommandHelpers[BSONSerializationPack.type] {

  import reactivemongo.bson.{
    BSONDocument, BSONDocumentReader, BSONDocumentWriter, BSONArray, BSONString, BSONInteger
  }

  val pack = BSONSerializationPack

  case object ListDatabases extends Command
    with CommandWithPack[pack.type] with CommandWithResult[List[String]]

  object Implicits {
    implicit object BSONWriter extends BSONDocumentWriter[ListDatabases.type] {
      def write(cmd: ListDatabases.type) =
        BSONDocument("listDatabases" -> BSONInteger(1))
    }
    implicit object BSONReader extends BSONDocumentReader[List[String]] {
      def read(result: BSONDocument): List[String] = {
        val opts = result.getAs[BSONArray]("databases").get.values.map {
          case db: BSONDocument =>
            val str = db.getAs[BSONString]("name").get.value.toString
            Some(str)
          case _ => None
        }.toList

        val allAreSomes = opts forall {
          case Some(_) => true
          case None => false
        }

        if (!allAreSomes) {
          throw new RuntimeException("databases field is missing")
        }

        opts.map(_.get)
      }
    }
  }
}

import scala.concurrent.{ ExecutionContext, Future }
import reactivemongo.api.{ BSONSerializationPack, GenericDB, MongoConnection }

object CustomCommandRunner {
  import ListDatabasesCommand._
  import ListDatabasesCommand.Implicits._

  def listDatabases(conn: MongoConnection
    )(implicit ec: ExecutionContext): Future[List[String]] = {

      val futureDb: Future[GenericDB[BSONSerializationPack.type]] =
        conn.database("admin")
      
      futureDb.flatMap(_.runCommand(ListDatabases))

    }
}
