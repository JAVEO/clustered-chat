name := """Clustered chat"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.7"

val akkaVersion = "2.4.2"

libraryDependencies ++= Seq(
  "org.reactivemongo" %% "play2-reactivemongo" % "0.11.12",
  "com.themillhousegroup" %% "play2-reactivemongo-mocks" % "0.11.11_0.6.38" % Test,
  // <from https://github.com/shiraeeshi/specs2-embedmongo/blob/list-databases/project/Build.scala>
  "de.svenkubiak" % "embedded-mongodb" % "4.2.9",
  // </from>
  "org.mongodb" % "mongo-java-driver" % "3.2.0" % Test,
  "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
  "com.typesafe.akka" %% "akka-contrib" % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
  "org.webjars" %% "webjars-play" % "2.4.0",
  "org.webjars" % "bootstrap" % "3.3.4",
  "org.webjars" % "font-awesome" % "4.7.0",
  "org.webjars" % "jquery" % "2.2.4",
  "org.webjars" % "handlebars" % "4.0.2"//,

  , specs2 % Test
)

resolvers ++= Seq(
  "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"
  , "Millhouse Bintray"  at "http://dl.bintray.com/themillhousegroup/maven"
)

LessKeys.compress in Assets := true

pipelineStages := Seq(digest)

includeFilter in (Assets, LessKeys.less) := "*.less"

concurrentRestrictions in Global += Tags.limit(Tags.Test, 1)
parallelExecution in Test := false

testOptions in Test += Tests.Argument(TestFrameworks.Specs2, "sequential")
javaOptions in Test ++= Seq("-Dlogger.resource=logback-test.xml", "-Dconfig.resource=application.test.conf")
