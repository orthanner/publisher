name := "Publisher"

version := "1.0"

scalaVersion := "2.11.5"

resolvers += "Spring IO" at "http://repo.spring.io/release"

val springVersion = "4.1.4.RELEASE"
val akkaVersion = "2.3.9"

libraryDependencies ++= Seq(
  "org.json4s" %% "json4s-jackson" % "3.2.11",
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
  "org.scala-lang.modules" %% "scala-async" % "0.9.2",
  "com.typesafe" % "config" % "1.2.1",
  "org.postgresql" % "postgresql" % "9.3-1101-jdbc41",
  "org.mariadb.jdbc" % "mariadb-java-client" % "1.1.7",
  "com.h2database" % "h2" % "1.4.181",
  "org.apache.commons" % "commons-dbcp2" % "2.0.1",
  "commons-codec" % "commons-codec" % "1.9",
  "org.springframework" % "spring-context" % springVersion,
  "org.springframework" % "spring-jdbc" % springVersion,
  "org.springframework" % "spring-tx" % springVersion,
  "com.netflix.rxjava" % "rxjava-scala" % "0.20.4",
  "org.scalatest" %% "scalatest" % "2.2.1" % "test",
  "junit" % "junit" % "4.11" % "test",
  "com.novocode" % "junit-interface" % "0.10" % "test",
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test"
)

scalacOptions in ThisBuild ++= Seq("-unchecked", "-deprecation", "-feature", "-language:postfixOps")
