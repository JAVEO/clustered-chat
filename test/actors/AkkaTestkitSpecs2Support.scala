package actors

import akka.actor._
import akka.testkit._
import org.specs2.mutable._
import org.specs2.execute._
import org.specs2.specification.Scope
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.Application
import scala.concurrent.{ ExecutionContext, Future, Await }
import scala.concurrent.duration._
import play.api.libs.json.{Writes, JsObject, JsValue}
//import reactivemongo.play.json._
//import reactivemongo.play.json.ImplicitBSONHandlers._
import play.modules.reactivemongo.json._
import play.modules.reactivemongo.json.collection._
//import play.api.libs.json.Reads._
import reactivemongo.bson.{
    BSONDocumentWriter, BSONDocumentReader, Macros, document
}
import reactivemongo.api.commands.MultiBulkWriteResult
import scala.util.{Try, Success, Failure}
import reactivemongo.api.{ DefaultDB, MongoConnection, MongoDriver }
import play.api.test.Helpers

abstract class AkkaTestkitSpecs2Support extends TestKit(ActorSystem())
  with After
  with ImplicitSender {

  def after() = system.shutdown()
}

object AkkaSpecsWithApp {
  def defaultApp = {
    new GuiceApplicationBuilder()
      .in(new java.io.File("conf/application.test.conf"))
      .in(play.api.Mode.Test)
      .configure("akka.remote.netty.tcp.port" -> 2554)
      .build
  }
}

abstract class AkkaSpecsWithApp(app: Application = AkkaSpecsWithApp.defaultApp) extends TestKit(ActorSystem())
  with Around
  with ImplicitSender
  with Scope
  {

  override def around[T : AsResult](t: => T): Result = {
    before()
    val result = Helpers.running(app)(AsResult.effectively(t))
    after()
    result
  }

  def before() {}
  def after() = system.shutdown()

  lazy val conf = play.api.Play.current.injector.instanceOf[play.api.Configuration]
}

object AkkaSpecsWithData {
  class CouldNotInsertException(
    message: String,
    cause: java.lang.Throwable = null) extends java.lang.Exception(message, cause)
}

abstract class AkkaSpecsWithData(
  dataToInsert: Map[String, Seq[JsObject]],
  app: Application = AkkaSpecsWithApp.defaultApp,
  durationToWaitForInserting: FiniteDuration = 5 seconds
  ) extends AkkaSpecsWithApp(app) {

    import AkkaSpecsWithData._

    def this(couples: (String, Seq[JsObject])*) = this(couples.toMap)
    def this(app: Application, couples: (String, Seq[JsObject])*) = this(couples.toMap, app)

    override def before {
      insertData()
    }

    def insertData() {
      import ExecutionContext.Implicits.global
      val mongouri = utilit.Conf.get.getString("mongodb.uri").get
      val driver = MongoDriver()
      val futureUri = Future.fromTry(MongoConnection.parseURI(mongouri))
      val futureConnection = futureUri.map(driver.connection(_))
      def db: Future[DefaultDB] = for {
        conn <- futureConnection
        uri <- futureUri
        dn = uri.db.get
        db <- conn.database(dn)
      } yield db

      def coll(name: String): Future[JSONCollection] = db.map(_.collection[JSONCollection](name))

      val futuresWithResults: List[Future[(String, MultiBulkWriteResult)]] =
        dataToInsert.toList map { case (name, values) =>
          /*
          val futureResult = for {
            collec <- coll(name)
            result <- collec.bulkInsert(ordered = true)(values: _*)
          } yield (name, result)
          */
          val futureResult = coll(name) flatMap { collec =>
            val docs = values.map(implicitly[collec.ImplicitlyDocumentProducer](_))
            collec.bulkInsert(ordered = true)(docs: _*) map { result =>
              (name, result)
            }
          }

          futureResult
        }

      val futureResults: Future[List[(String, MultiBulkWriteResult)]] =
        Future.sequence(futuresWithResults)

      val results = Try(Await.result(futureResults, durationToWaitForInserting))

      results match {
        case Success(listOfCouples) =>
          val wasOk = listOfCouples.forall{
            case (name, result) => result.ok
          } 
          if (!wasOk) {
            throw new CouldNotInsertException(s"not all results was ok: ${results}")
          }
        case Failure(e) =>
          throw new CouldNotInsertException("the error was thrown", e)
      }
    }

}

