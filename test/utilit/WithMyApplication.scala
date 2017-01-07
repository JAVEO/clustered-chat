package utilit

import org.specs2.execute.{ AsResult, Result }
import org.specs2.mutable.Around
import org.specs2.specification.Scope
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.Application
import play.api.test._

/**
 * ----------------------------------------------------------------------
 * Copy of play.api.test.WithApplication
 * (https://github.com/playframework/playframework/blob/master/framework/src/play-specs2/src/main/scala/play/api/test/Specs.scala)
 * adding Specs2 dependency causes the error
 * (error while loading Action, class file '/home/travis/.ivy2/cache/org.specs2/specs2_2.11/jars/specs2_2.11-2.3.12.jar(org/specs2/specification/Action.class)' is broken)
 * (see https://travis-ci.org/JAVEO/clustered-chat/builds/189750199)
 * this is a temporary workaround 
 * ----------------------------------------------------------------------
 *
 * Used to run specs within the context of a running application.
 *
 * @param app The fake application
 */
abstract class WithMyApplication(val app: Application = GuiceApplicationBuilder().build()) extends Around with Scope {

  def this(builder: GuiceApplicationBuilder => GuiceApplicationBuilder) {
    this(builder(GuiceApplicationBuilder()).build())
  }

  implicit def implicitApp = app
  implicit def implicitMaterializer = app.materializer
  override def around[T: AsResult](t: => T): Result = {
    Helpers.running(app)(AsResult.effectively(t))
  }
}
