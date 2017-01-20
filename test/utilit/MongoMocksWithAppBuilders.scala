package utilit

import play.api.libs.json._
import play.modules.reactivemongo.{ReactiveMongoApi,ReactiveMongoModule}
import reactivemongo.api.DefaultDB
import com.themillhousegroup.reactivemongo.mocks.MongoMocks
import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import org.specs2.mutable._

trait MongoMocksWithAppBuilders extends MongoMocks { this: Specification =>

  var insertionEventHandlers: Map[String, Seq[JsObject => Unit]] = Map.empty

	def watchInsertion(name: String)(func: JsObject => Unit) {
		val old = insertionEventHandlers.get(name) match {
			case Some(handlers) => handlers
			case None => Nil
		}
    insertionEventHandlers += name -> (old :+ func)
	}

  def app = appWithData()
  def appCatchingInsertions(collectionNames: String*) = appWithData(collectionNames.map((_, List.empty[JsObject])): _*)
  def appWithData(couplesToInsert: (String, Seq[JsObject])*) = delayedApp(0 seconds, couplesToInsert: _*)

  def delayedApp(durationToWaitForDb: FiniteDuration,
          couplesToInsert: (String, Seq[JsObject])*) = {

    val dataToInsert: Map[String, Seq[JsObject]] = couplesToInsert.toMap

    val portNum = 2554
    val mockapi = mock[ReactiveMongoApi]
    implicit val mockedDatabase = if (dataToInsert.isEmpty) {mockDB} else {
      val m = mock[DefaultDB]
      m.name answers {_ => "mockedDatabase"}
      m
    }
    dataToInsert.foreach { case (name, jsObjects) =>
      val collection = mockedCollection(name)

      givenMongoFindOperatesOn(collection, jsObjects)

			// <from origin=play2-reactivemongo-mocks>
      // Nothing to mock an answer for - it's unchecked - but we record the insert to be useful
			collection.uncheckedInsert(anyJs)(anyPackWrites) answers { args =>
				val jsObject = firstArg(args)
				for {
          handlers <- insertionEventHandlers.get(name)
          func <- handlers
        } {
          func(jsObject)
        }
			}

			collection.insert(anyJs, anyWriteConcern)(anyPackWrites, anyEC) answers { args =>
				val jsObject = firstArg(args)
				for {
          handlers <- insertionEventHandlers.get(name)
          func <- handlers
        } {
          func(jsObject)
        }
        val ok = true
				Future.successful(mockResult(ok))
			}
			// </from>
    }
    import scala.concurrent.ExecutionContext.Implicits.global
    val futureDb = scala.concurrent.Future {
      if (durationToWaitForDb > 0.seconds) {
        Thread.sleep(durationToWaitForDb.toMillis)
      }
      mockedDatabase
    }
    mockapi.database returns futureDb

    new GuiceApplicationBuilder()
      .in(new java.io.File("conf/application.conf"))
      .in(play.api.Mode.Test)
      .configure("akka.remote.netty.tcp.port" -> portNum)
      .disable[ReactiveMongoModule]
      .overrides(
        bind[ReactiveMongoApi].toInstance(mockapi))
      .build
  }

  def appWithoutDb = {
    val portNum = 2554
    import scala.concurrent.ExecutionContext.Implicits.global
    val mockapi = mock[ReactiveMongoApi]
    val p  = Promise[DefaultDB]()
    p failure(new Exception("app with mocked reactivemongoapi returns no db"))
    mockapi.database returns p.future
    new GuiceApplicationBuilder()
      .in(new java.io.File("conf/application.conf"))
      .in(play.api.Mode.Test)
      .configure("akka.remote.netty.tcp.port" -> portNum)
      .disable[ReactiveMongoModule]
      .overrides(
        bind[ReactiveMongoApi].toInstance(mockapi))
      .build
  }
}
