name := "NASA APOD grabber"

version := "1.0"

scalaVersion := "2.12.2"

// Json parser
libraryDependencies += "org.json4s" %% "json4s-native" % "3.5.2"

scalacOptions ++= Seq("-unchecked", "-deprecation")
