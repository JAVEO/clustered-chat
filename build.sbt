name := """Clustered chat"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.7"

val akkaVersion = "2.4.2"

libraryDependencies ++= Seq(
  "org.reactivemongo" %% "play2-reactivemongo" % "0.11.12",
  "com.github.athieriot" %% "specs2-embedmongo" % "0.7.0" % Test,
  "org.mongodb" % "mongo-java-driver" % "3.2.0" % Test,
  "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
  "com.typesafe.akka" %% "akka-contrib" % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
  "org.webjars" %% "webjars-play" % "2.4.0",
  "org.webjars" % "bootstrap" % "3.3.4",
  "org.webjars" % "font-awesome" % "4.7.0",
  "org.webjars" % "jquery" % "2.2.4",
  "org.webjars" % "handlebars" % "4.0.2",
  //"org.specs2" %% "specs2" % "2.3.12" % Test
  specs2 % Test
)

resolvers ++= Seq(
  "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"
  //, "Millhouse Bintray"  at "http://dl.bintray.com/themillhousegroup/maven"
)

LessKeys.compress in Assets := true

pipelineStages := Seq(digest)

includeFilter in (Assets, LessKeys.less) := "*.less"

concurrentRestrictions in Global += Tags.limit(Tags.Test, 1)
parallelExecution in Test := false

//scalacOptions += "-Ylog-classpath"
javaOptions in Test ++= Seq("-Dlogger.resource=logback-test.xml", "-Dconfig.resource=application.test.conf")
