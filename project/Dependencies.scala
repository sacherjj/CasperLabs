import sbt._

object Dependencies {

  val osClassifier: String = Detector.detect(Seq("fedora")).osClassifier

  val circeVersion   = "0.11.1"
  val http4sVersion  = "0.20.1"
  val kamonVersion   = "1.1.3"
  val catsVersion    = "1.6.0"
  val catsMtlVersion = "0.5.0"
  val doobieVersion  = "0.7.0"
  val fs2Version = "1.0.4"

  val julToSlf4j          = "org.slf4j"           % "jul-to-slf4j"          % "1.7.25"
  val bitcoinjCore        = "org.bitcoinj"        % "bitcoinj-core"         % "0.14.6"
  val bouncyProvCastle    = "org.bouncycastle"    % "bcprov-jdk15on"        % "1.61"
  val bouncyPkixCastle    = "org.bouncycastle"    % "bcpkix-jdk15on"        % "1.61"
  val catsCore            = "org.typelevel"       %% "cats-core"            % catsVersion
  val catsPar             = "io.chrisdavenport"   %% "cats-par"             % "0.2.1"
  val catsLawsTest        = "org.typelevel"       %% "cats-laws"            % catsVersion % "test"
  val catsLawsTestkitTest = "org.typelevel"       %% "cats-testkit"         % catsVersion % "test"
  val catsEffect          = "org.typelevel"       %% "cats-effect"          % "1.3.0"
  val catsEffectLaws      = "org.typelevel"       %% "cats-effect-laws"     % "1.3.0" % "test"
  val catsMtl             = "org.typelevel"       %% "cats-mtl-core"        % catsMtlVersion
  val catsMtlLawsTest     = "org.typelevel"       %% "cats-mtl-laws"        % catsMtlVersion % "test"
  val meowMtl             = "com.olegpy"          %% "meow-mtl"             % "0.2.1"
  val circeCore           = "io.circe"            %% "circe-core"           % circeVersion
  val circeGeneric        = "io.circe"            %% "circe-generic"        % circeVersion
  val circeGenericExtras  = "io.circe"            %% "circe-generic-extras" % circeVersion
  val circeLiteral        = "io.circe"            %% "circe-literal"        % circeVersion
  val circeParser         = "io.circe"            %% "circe-parser"         % circeVersion
  val sangria             = "org.sangria-graphql" %% "sangria"              % "1.4.2"
  val guava               = "com.google.guava"    % "guava"                 % "24.1.1-jre"
  val hasher              = "com.roundeights"     %% "hasher"               % "1.2.0"
  val http4sBlazeServer   = "org.http4s"          %% "http4s-blaze-server"  % http4sVersion
  val javaWebsocket       = "org.java-websocket"  % "Java-WebSocket"        % "1.4.0"
  val http4sCirce         = "org.http4s"          %% "http4s-circe"         % http4sVersion
  val http4sDSL           = "org.http4s"          %% "http4s-dsl"           % http4sVersion
  val jaxb                = "javax.xml.bind"      % "jaxb-api"              % "2.3.1"
  val jline = ("org.scala-lang" % "jline" % "2.10.7")
    .exclude("org.fusesource.jansi", "jansi")
  // see https://jitpack.io/#rchain/kalium
  val kalium             = "com.github.rchain" % "kalium"                % "0.8.1"
  val kamonCore          = "io.kamon"          %% "kamon-core"           % kamonVersion
  val kamonSystemMetrics = "io.kamon"          %% "kamon-system-metrics" % "1.0.0"
  val kamonPrometheus    = "io.kamon"          %% "kamon-prometheus"     % "1.1.1"
  val kamonInfluxDb      = "io.kamon"          %% "kamon-influxdb"       % "1.0.2"
  val kamonZipkin        = "io.kamon"          %% "kamon-zipkin"         % "1.0.0"
  val lightningj = ("org.lightningj" % "lightningj" % "0.5.0-Beta-rc2")
    .intransitive() //we only use the lib for one util class (org.lightningj.util.ZBase32) that has no dependencies
  val lmdbjava               = "org.lmdbjava"               % "lmdbjava"                        % "0.6.1"
  val logbackClassic         = "ch.qos.logback"             % "logback-classic"                 % "1.2.3"
  val janino                 = "org.codehaus.janino"        % "janino"                          % "3.0.12"
  val lz4                    = "org.lz4"                    % "lz4-java"                        % "1.5.0"
  val monix                  = "io.monix"                   %% "monix"                          % "3.0.0-RC2"
  val scalaLogging           = "com.typesafe.scala-logging" %% "scala-logging"                  % "3.9.0"
  val scalaUri               = "io.lemonlabs"               %% "scala-uri"                      % "1.1.5"
  val scalacheck             = "org.scalacheck"             %% "scalacheck"                     % "1.13.5" % "test"
  val scalacheckNoTest       = "org.scalacheck"             %% "scalacheck"                     % "1.13.5"
  val scalacheckShapeless    = "com.github.alexarchambault" %% "scalacheck-shapeless_1.13"      % "1.1.8" % "test"
  val graphvizJava           = "guru.nidi"                  % "graphviz-java"                   % "0.8.3"
  val scalactic              = "org.scalactic"              %% "scalactic"                      % "3.0.5" % "test"
  val scalapbCompiler        = "com.thesamet.scalapb"       %% "compilerplugin"                 % scalapb.compiler.Version.scalapbVersion
  val scalapbRuntime         = "com.thesamet.scalapb"       %% "scalapb-runtime"                % scalapb.compiler.Version.scalapbVersion % "protobuf"
  val scalapbRuntimeLib      = "com.thesamet.scalapb"       %% "scalapb-runtime"                % scalapb.compiler.Version.scalapbVersion
  val scalapbRuntimegGrpc    = "com.thesamet.scalapb"       %% "scalapb-runtime-grpc"           % scalapb.compiler.Version.scalapbVersion
  val grpcNetty              = "io.grpc"                    % "grpc-netty"                      % scalapb.compiler.Version.grpcJavaVersion
  val nettyAll               = "io.netty"                   % "netty-all"                       % "4.1.22.Final"
  val nettyTransNativeEpoll  = "io.netty"                   % "netty-transport-native-epoll"    % "4.1.22.Final" classifier "linux-x86_64"
  val nettyTransNativeKqueue = "io.netty"                   % "netty-transport-native-kqueue"   % "4.1.22.Final" classifier "osx-x86_64"
  val nettyBoringSsl         = "io.netty"                   % "netty-tcnative-boringssl-static" % "2.0.8.Final"
  val nettyTcnative          = "io.netty"                   % "netty-tcnative"                  % "2.0.8.Final" classifier osClassifier
  val nettyTcnativeLinux     = "io.netty"                   % "netty-tcnative"                  % "2.0.8.Final" classifier "linux-x86_64"
  val nettyTcnativeFedora    = "io.netty"                   % "netty-tcnative"                  % "2.0.8.Final" classifier "linux-x86_64-fedora"
  val scalatest              = "org.scalatest"              %% "scalatest"                      % "3.0.5" % "test"
  val scallop                = "org.rogach"                 %% "scallop"                        % "3.1.4"
  val scodecCore             = "org.scodec"                 %% "scodec-core"                    % "1.10.3"
  val scodecCats             = "org.scodec"                 %% "scodec-cats"                    % "0.8.0"
  val scodecBits             = "org.scodec"                 %% "scodec-bits"                    % "1.1.7"
  val shapeless              = "com.chuusai"                %% "shapeless"                      % "2.3.3"
  val magnolia               = "com.propensive"             %% "magnolia"                       % "0.10.0"
  val weupnp                 = "org.bitlet"                 % "weupnp"                          % "0.1.4"
  val gatlingHighcharts      = "io.gatling.highcharts"      % "gatling-charts-highcharts"       % "3.0.3" % "test"
  val gatlingFramework       = "io.gatling"                 % "gatling-test-framework"          % "3.0.3" % "test"
  val gatlingGrpc            = "com.github.phisgr"          %% "gatling-grpc"                   % "0.3.0" % "test"
  // see https://jitpack.io/#rchain/secp256k1-java
  val secp256k1Java = "com.github.rchain" % "secp256k1-java" % "0.1"
  val tomlScala     = "tech.sparse"       %% "toml-scala"    % "0.1.1"
  val refinement    = "eu.timepit"        %% "refined"       % "0.9.5"
  val apacheCommons = "commons-io"        % "commons-io"     % "2.6"
  val sqlLite       = "org.xerial"        % "sqlite-jdbc"    % "3.28.0"
  val doobieCore    = "org.tpolecat"      %% "doobie-core"   % doobieVersion
  val doobieHikari  = "org.tpolecat"      %% "doobie-hikari" % doobieVersion
  val flyway        = "org.flywaydb"      % "flyway-core"    % "5.2.4"
  val fs2           = "co.fs2"            %% "fs2-core"      % fs2Version

  val overrides = Seq(
    catsCore,
    catsEffect,
    catsLawsTest,
    shapeless,
    guava,
    scodecBits,
    scalacheckNoTest,
    //overrides for transitive dependencies (we don't use them directly, hence no val-s)
    "org.typelevel"            %% "machinist"              % "0.6.5",
    "com.lihaoyi"              %% "sourcecode"             % "0.1.4",
    "org.scala-lang.modules"   %% "scala-xml"              % "1.1.0",
    "com.google.code.findbugs" % "jsr305"                  % "3.0.2",
    "com.google.errorprone"    % "error_prone_annotations" % "2.1.2",
    "com.github.jnr"           % "jnr-ffi"                 % "2.1.7"
  )

  private val kindProjector    = compilerPlugin("org.spire-math" %% "kind-projector"     % "0.9.8")
  private val betterMonadicFor = compilerPlugin("com.olegpy"     %% "better-monadic-for" % "0.3.0")
  private val macroParadise = compilerPlugin(
    "org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full
  )

  private val testing = Seq(scalactic, scalatest, scalacheck, scalacheckShapeless)

  private val logging = Seq(scalaLogging, logbackClassic, janino, julToSlf4j)

  private val circeDependencies: Seq[ModuleID] =
    Seq(circeCore, circeGeneric, circeGenericExtras, circeParser, circeLiteral)

  private val http4sDependencies: Seq[ModuleID] =
    Seq(http4sDSL, http4sBlazeServer, http4sCirce)

  val protobufDependencies: Seq[ModuleID] =
    Seq(scalapbRuntime)

  val protobufLibDependencies: Seq[ModuleID] =
    Seq(scalapbRuntimeLib)

  val kamonDependencies: Seq[ModuleID] =
    Seq(kamonCore, kamonSystemMetrics, kamonPrometheus, kamonZipkin, kamonInfluxDb)

  val apiServerDependencies: Seq[ModuleID] =
    http4sDependencies ++ circeDependencies

  val commonDependencies: Seq[ModuleID] =
    Seq(
      compilerPlugin("com.github.ghik" %% "silencer-plugin" % "1.4.1"),
      "com.github.ghik" %% "silencer-lib" % "1.4.1" % Provided
    ) ++ logging ++ testing :+ kindProjector :+ macroParadise :+ betterMonadicFor

  val gatlingDependencies: Seq[ModuleID] = Seq(
    gatlingFramework,
    gatlingGrpc,
    gatlingHighcharts,
    scalapbRuntime,
    scalapbRuntimegGrpc,
    grpcNetty
  )

  //needed because Gatling transitively bring binary incompatible dependencies
  val gatlingOverrides: Seq[ModuleID] = Seq(
    "com.thesamet.scalapb"       %% "compilerplugin"       % "0.8.2",
    "com.thesamet.scalapb"       %% "scalapb-runtime"      % "0.8.2",
    "com.thesamet.scalapb"       %% "scalapb-runtime-grpc" % "0.8.2",
    "io.grpc"                    % "grpc-netty"            % "1.15.1",
    "io.netty"                   % "netty-buffer"          % "4.1.33.Final",
    "io.netty"                   % "netty-handler"         % "4.1.33.Final",
    "io.netty"                   % "netty-handler-proxy"   % "4.1.33.Final",
    "io.netty"                   % "netty-codec"           % "4.1.33.Final",
    "io.netty"                   % "netty-codec-http"      % "4.1.33.Final",
    "io.netty"                   % "netty-codec-http2"     % "4.1.33.Final",
    "com.typesafe.scala-logging" %% "scala-logging"        % "3.9.2",
    "com.google.protobuf"        % "protobuf-java"         % "3.6.1"
  )
}
