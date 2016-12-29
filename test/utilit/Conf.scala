package utilit

import play.api.Configuration
import com.typesafe.config.ConfigFactory

object Conf {
  val get = Configuration(ConfigFactory.load("conf/application.conf"))
}
