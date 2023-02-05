lazy val akkaHttpVersion = "10.2.8"
lazy val akkaVersion = "2.6.18"
lazy val elastic4sVersion = "7.17.2"
lazy val buildVersion = "1.4.0-SNAPSHOT"
fork := true

import sbt.Package.ManifestAttributes

packageOptions := Seq(ManifestAttributes(("Implementation-Version", git.gitHeadCommit.value.get.take(10))))

lazy val root = (project in file("."))
  .enablePlugins(JDKPackagerPlugin, JavaAppPackaging, DockerPlugin)
  .settings(
    organization := "ai.taiyo",
    name := "TaiyoServe",
    scalaVersion := "2.13.8",
    version := buildVersion,
    maintainer := "mahendra@taiyo.ai",
    jdkPackagerJVMArgs := Seq("-Xms2048m", "-Xmx4096m"),
    dockerBaseImage := "--platform=linux/amd64 openjdk:11", //"openjdk:8-jdk-slim-buster", //default to openjdk:8
    dockerExposedPorts := Seq(8080),
    dockerAliases := Seq(
      DockerAlias(Some("965994533236.dkr.ecr.ap-south-1.amazonaws.com"), None, name = "taiyoserve", tag = Some("latest"))
      , DockerAlias(Some("965994533236.dkr.ecr.ap-south-1.amazonaws.com"), None, name = "taiyoserve", tag = Some(buildVersion))
      , DockerAlias(Some("965994533236.dkr.ecr.ap-south-1.amazonaws.com"), None, name = "taiyoserve", tag = Some(s"commit-${git.gitHeadCommit.value.get.take(10)}"))
    ),
    dockerUsername := Some("mahendra@taiyo.ai"),
    dockerEnvVars := Map[String, String](
      "ENV" -> "prod",
      "API_HOST" -> "platform.taiyo.ai",
      "BASE_PATH" -> "taiyoserve",
      "PORT" -> "8080",
      "ES_HOST" -> "localhost",
      "ES_PORT" -> "9200",
      "ES_SCHEME" -> "http",
      "ES_AUTH_TYPE" -> "Basic",
      "ES_USER" -> "",
      "ES_PASSWORD" -> "",
      "OS_HOST" -> "localhost",
      "OS_PORT" -> "9200",
      "OS_SCHEME" -> "http",
      "OS_AUTH_TYPE" -> "Basic",
      "OS_USER" -> "",
      "OS_PASSWORD" -> "",
      "MONGO_HOST_URL" -> "mongodb://localhost:27017/",
      "MONGO_DATABASE" -> "timeseries",
      "AWS_ACCESS_KEY_ID" -> "timeseries",
      "AWS_SECRET_ACCESS_KEY" -> "timeseries",
      "ELASTIC_APM_ENVIRONMENT" -> "local",
      "ELASTIC_APM_SERVICE_NAME" -> "Taiyo-Serve",
      "ELASTIC_APM_SERVER_URL" -> "http://localhost:8200",
      "ELASTIC_APM_API_KEY" -> "base64 coded key",
    ),
    resolvers ++= Seq(
      "sona" at "https://repo1.maven.org/maven2"
    ),
    libraryDependencies ++= Seq(
      "co.elastic.apm" % "elastic-apm-agent" % "1.33.0",
      "co.elastic.apm" % "apm-agent-attach" % "1.33.0",
      "org.apache.logging.log4j" % "log4j-core" % "2.17.2",
      "co.elastic.apm" % "apm-agent-api" % "1.33.0",
      "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
      "org.json4s" %% "json4s-native" % "4.0.2",
      "com.sksamuel.elastic4s" %% "elastic4s-json-json4s" % elastic4sVersion,
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.12.2",
      "com.fasterxml.jackson.dataformat" % "jackson-dataformat-csv" % "2.12.2",
      "org.mongodb.scala" %% "mongo-scala-driver" % "4.4.2",
      "org.mongodb.scala" %% "mongo-scala-bson" % "4.4.2",
      "com.typesafe" % "config" % "1.4.2",
      "org.apache.commons" % "commons-lang3" % "3.12.0",
      "com.sksamuel.elastic4s" %% "elastic4s-client-esjava" % elastic4sVersion,
      "com.amazonaws" % "aws-java-sdk" % "1.11.221",
      "org.elasticsearch.client" % "elasticsearch-rest-high-level-client" % elastic4sVersion,
      // test kit
      "com.sksamuel.elastic4s" %% "elastic4s-testkit" % elastic4sVersion % Test,
      "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test,
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
      "org.scalatest" %% "scalatest" % "3.1.4" % Test
    ),
  )
