resolvers ++= Seq(
  "netlogo-extension-plugin" at "https://dl.cloudsmith.io/public/netlogo/netlogo-extension-plugin/maven/"
)

addSbtPlugin("org.nlogo" % "netlogo-extension-plugin" % "7.0.4")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.10.4")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "1.2.0")
