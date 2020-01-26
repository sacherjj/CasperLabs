addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.19")
// Yes it's weird to do the following, but it's what is mandated by the scalapb documentation
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.7.4"

addSbtPlugin("org.scalameta"      % "sbt-scalafmt"        % "2.0.7")
addSbtPlugin("com.eed3si9n"       % "sbt-buildinfo"       % "0.9.0")
addSbtPlugin("com.typesafe.sbt"   % "sbt-native-packager" % "1.4.1")
addSbtPlugin("pl.project13.scala" % "sbt-jmh"             % "0.3.7")
addSbtPlugin("ch.epfl.scala"      % "sbt-bloop"           % "1.4.0-RC1")
addSbtPlugin("io.gatling"         % "gatling-sbt"         % "3.0.0")
addSbtPlugin("com.typesafe.sbt"   % "sbt-git"             % "1.0.0")
