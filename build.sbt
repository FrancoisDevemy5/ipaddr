lazy val root = (project in file("."))
  .settings(
    name := "ipaddr",
    organization := "com.risksense",
    version := "2.0.0",
    scalaVersion := "3.3.3",
    scalacOptions ++= Seq(
      "-feature",
      "-deprecation",
      "-explain"),
    resolvers += Resolver.sonatypeRepo("releases"),
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.4.14",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
      "org.scalatest" %% "scalatest" % "3.2.18" % "test"
    )
  )
