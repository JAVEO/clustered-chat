package com.github.athieriot.util

// see https://gist.github.com/sgodbillon/10829302

import reactivemongo.core.commands._
import reactivemongo.bson._
import reactivemongo.bson.DefaultBSONHandlers._
import reactivemongo.bson.BSONInteger
import reactivemongo.bson.BSONString
import scala.Some

// { listDatabases: 1 }
/*
listDatabases returns a document for each database
Each document contains a name field with the database name,
a sizeOnDisk field with the total size of the database file on disk in bytes,
and an empty field specifying whether the database has any data.
*/
object ListDatabases extends AdminCommand[List[String]] {

  object ResultMaker extends BSONCommandResultMaker[List[String]] {
    def apply(doc: BSONDocument) = {
      CommandError.checkOk(doc, Some("listDatabases")).toLeft {
        val opts = doc.getAs[BSONArray]("databases").get.values.map {
          case db: BSONDocument =>
            val str = db.getAs[BSONString]("name").get.value.toString
            Some(str)
          case _ =>
            None
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

  def makeDocuments = BSONDocument("listDatabases" -> BSONInteger(1))
}
