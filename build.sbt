name := "NASA APOD grabber"

version := "1.0"

scalaVersion := "2.12.2"

// Json parser
libraryDependencies += "org.json4s" %% "json4s-native" % "3.5.2"
// Time manipulation
//libraryDependencies += "com.github.nscala-time" %% "nscala-time" % "2.16.0"


scalacOptions ++= Seq("-unchecked", "-deprecation")
