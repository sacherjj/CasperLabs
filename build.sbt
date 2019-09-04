import Dependencies._
import com.typesafe.sbt.packager.docker._

//allow stopping sbt tasks using ctrl+c without killing sbt itself
Global / cancelable := true

//disallow any unresolved version conflicts at all for faster feedback
Global / conflictManager := ConflictManager.strict
//resolve all version conflicts explicitly
Global / dependencyOverrides := Dependencies.overrides

// Keeping all the .proto definitions in a common place so we can use `include` to factor out common messages.
val protobufDirectory = file("protobuf")
// Protos can import any other using the full path within `protobuf`. This filter reduces the list
// for which we actually generate .scala source, so we don't get duplicates between projects.
def protobufSubDirectoryFilter(subdirs: String*) = {
  import java.nio.file.Paths // Handle backslash on Windows.
  (f: File) =>
    f.getName.endsWith(".proto") && // Not directories or other artifacts.
      subdirs.map(Paths.get(_)).exists(p => f.toPath.getParent.endsWith(p))
}

lazy val projectSettings = Seq(
  organization := "io.casperlabs",
  scalaVersion := "2.12.9",
  version := "0.1.0-SNAPSHOT",
  resolvers ++= Seq(
    Resolver.sonatypeRepo("releases"),
    Resolver.sonatypeRepo("snapshots"),
    "jitpack" at "https://jitpack.io"
  ),
  scalafmtOnCompile := true,
  scapegoatVersion in ThisBuild := "1.3.4",
  testOptions in Test += Tests.Argument("-oD"), //output test durations
  dependencyOverrides ++= Seq(
    "io.kamon" %% "kamon-core" % kamonVersion
  ),
  javacOptions ++= (sys.env.get("JAVAC_VERSION") match {
    case None    => Seq()
    case Some(v) => Seq("-source", v, "-target", v)
  }),
  Test / fork := false, // Forking may cause "Reporter closed abruptly..." messages due to non-serializable exceptions.
  Test / parallelExecution := false,
  Test / testForkedParallel := false,
  IntegrationTest / fork := true,
  IntegrationTest / parallelExecution := false,
  IntegrationTest / testForkedParallel := false,
  Compile / doc / sources := Seq.empty,
  Compile / packageDoc / publishArtifact := false
)

lazy val coverageSettings = Seq(
  coverageMinimum := 90,
  coverageFailOnMinimum := false,
  coverageExcludedFiles := Seq(
    (javaSource in Compile).value,
    (sourceManaged in Compile).value.getPath ++ "/.*"
  ).mkString(";")
)

// Before starting sbt export YOURKIT_AGENT set to the profiling agent appropriate
// for your OS (https://www.yourkit.com/docs/java/help/agent.jsp)
lazy val profilerSettings = Seq(
  javaOptions in run ++= sys.env
    .get("YOURKIT_AGENT")
    .map(agent => s"-agentpath:$agent=onexit=snapshot,sampling")
    .toSeq,
  javaOptions in reStart ++= (javaOptions in run).value
)

lazy val commonSettings = projectSettings ++ coverageSettings ++ CompilerSettings.options ++ profilerSettings

lazy val jmhSettings = Seq(
  sourceDirectory in Jmh := (sourceDirectory in Test).value,
  classDirectory in Jmh := (classDirectory in Test).value,
  dependencyClasspath in Jmh := (dependencyClasspath in Test).value,
  // rewire tasks, so that 'jmh:run' automatically invokes 'jmh:compile' (otherwise a clean 'jmh:run' would fail)
  compile in Jmh := (compile in Jmh).dependsOn(compile in Test).value,
  run in Jmh := (run in Jmh).dependsOn(Keys.compile in Jmh).evaluated
)

lazy val shared = (project in file("shared"))
  .settings(commonSettings: _*)
  .settings(
    version := "0.1",
    libraryDependencies ++= commonDependencies ++ Seq(
      fs2,
      catsCore,
      catsPar,
      catsEffect,
      catsEffectLaws,
      catsMtl,
      meowMtl,
      lz4,
      monix,
      scodecCore,
      scodecBits,
      scalapbRuntimegGrpc,
      catsLawsTest,
      catsLawsTestkitTest
    )
  )

lazy val graphz = (project in file("graphz"))
  .settings(commonSettings: _*)
  .settings(
    version := "0.1",
    libraryDependencies ++= commonDependencies ++ Seq(
      catsCore,
      catsEffect,
      catsMtl
    )
  )
  .dependsOn(shared)

lazy val casper = (project in file("casper"))
  .settings(commonSettings: _*)
  .settings(
    name := "casper",
    libraryDependencies ++= commonDependencies ++ protobufLibDependencies ++ Seq(
      catsCore,
      catsMtl,
      monix,
      nettyAll,
      nettyTransNativeEpoll,
      nettyTransNativeKqueue
    )
  )
  .dependsOn(
    storage        % "compile->compile;test->test",
    comm           % "compile->compile;test->test",
    shared         % "compile->compile;test->test",
    smartContracts % "compile->compile;test->test",
    crypto,
    models
  )

lazy val comm = (project in file("comm"))
  .settings(commonSettings: _*)
  .settings(
    version := "0.1",
    dependencyOverrides += "org.slf4j" % "slf4j-api" % "1.7.25",
    libraryDependencies ++= commonDependencies ++ kamonDependencies ++ protobufDependencies ++ Seq(
      grpcNetty,
      nettyBoringSsl,
      scalapbRuntimegGrpc,
      scalaUri,
      weupnp,
      hasher,
      catsCore,
      catsMtl,
      monix,
      guava,
      refinement
    ),
    PB.protoSources in Compile := Seq(protobufDirectory),
    includeFilter in PB.generate := new SimpleFileFilter(
      protobufSubDirectoryFilter(
        "io/casperlabs/comm/discovery",
        "io/casperlabs/comm/gossiping",
        "io/casperlabs/comm/protocol/routing" // TODO: Eventually remove.
      )
    ),
    PB.targets in Compile := Seq(
      scalapb.gen(flatPackage = true) -> (sourceManaged in Compile).value,
      grpcmonix.generators
        .GrpcMonixGenerator(flatPackage = true) -> (sourceManaged in Compile).value
    )
  )
  .dependsOn(shared % "compile->compile;test->test", crypto, models)

lazy val crypto = (project in file("crypto"))
  .settings(commonSettings: _*)
  .settings(
    name := "crypto",
    libraryDependencies ++= commonDependencies ++ protobufLibDependencies ++ Seq(
      guava,
      bouncyProvCastle,
      bouncyPkixCastle,
      scalacheckNoTest,
      kalium,
      jaxb,
      secp256k1Java,
      scodecBits
    ),
    fork := true
  )
  .dependsOn(shared)

lazy val models = (project in file("models"))
  .settings(commonSettings: _*)
  .settings(
    libraryDependencies ++= commonDependencies ++ protobufDependencies ++ Seq(
      catsCore,
      magnolia,
      scalapbCompiler,
      scalacheck,
      scalapbRuntimegGrpc
    ),
    // TODO: As we refactor the interfaces this project should only depend on consensus
    // related models, ones that get stored, passed to client. The client for example
    // shouldn't transitively depend on node-to-node and node-to-EE interfaces.
    PB.protoSources in Compile := Seq(
      protobufDirectory
    ),
    includeFilter in PB.generate := new SimpleFileFilter(
      protobufSubDirectoryFilter(
        "google/api",
        "io/casperlabs/casper/consensus",
        "io/casperlabs/casper/protocol", // TODO: Eventually remove.
        "io/casperlabs/ipc"
      )
    ),
    PB.targets in Compile := Seq(
      scalapb.gen(flatPackage = true) -> (sourceManaged in Compile).value,
      grpcmonix.generators
        .GrpcMonixGenerator(flatPackage = true) -> (sourceManaged in Compile).value
    )
  )
  .dependsOn(crypto, shared % "compile->compile;test->test")

val nodeAndClientVersion = "0.6.0"

lazy val node = (project in file("node"))
  .settings(commonSettings: _*)
  .enablePlugins(RpmPlugin, DebianPlugin, JavaAppPackaging, BuildInfoPlugin)
  .settings(
    version := nodeAndClientVersion,
    name := "node",
    maintainer := "CasperLabs, LLC. <info@casperlabs.io>",
    packageName := "casperlabs-node",
    packageName in Docker := "node",
    executableScriptName := "casperlabs-node",
    packageSummary := "CasperLabs Node",
    packageDescription := "CasperLabs Node - the Casperlabs blockchain node server software.",
    libraryDependencies ++=
      apiServerDependencies ++ commonDependencies ++ kamonDependencies ++ protobufDependencies ++ Seq(
        catsCore,
        grpcNetty,
        jline,
        scallop,
        scalaUri,
        scalapbRuntimegGrpc,
        tomlScala,
        sangria,
        javaWebsocket
      ),
    PB.protoSources in Compile := Seq(protobufDirectory),
    includeFilter in PB.generate := new SimpleFileFilter(
      protobufSubDirectoryFilter(
        "google/api",
        "io/casperlabs/node/api"
      )
    ),
    // Generating into /protobuf because of a clash with sbt-buildinfo: https://github.com/thesamet/sbt-protoc/issues/8
    PB.targets in Compile := Seq(
      scalapb.gen(flatPackage = true) -> (sourceManaged in Compile).value / "protobuf",
      grpcmonix.generators
        .GrpcMonixGenerator(flatPackage = true) -> (sourceManaged in Compile).value / "protobuf"
    ),
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion, git.gitHeadCommit),
    buildInfoPackage := "io.casperlabs.node",
    mainClass in assembly := Some("io.casperlabs.node.Main"),
    assemblyMergeStrategy in assembly := {
      case x if x.endsWith("io.netty.versions.properties") => MergeStrategy.first
      case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
    },
    /*
     * This monstrosity exists because
     * a) we want to get rid of annoying JVM >= 9 warnings,
     * b) we must support Java 8 for RedHat (see below) and
     * c) sbt-native-packager puts bashScriptExtraDefines before it
     *    initializes all useful variables (like $java_version).
     *
     * This won't work if someone passes -no-version-check command line
     * argument to casperlabs-node. They most probably know what they're doing.
     *
     * https://unix.stackexchange.com/a/29742/124070
     * Thanks Gilles!
     */
    bashScriptExtraDefines += """
      eval "original_$(declare -f java_version_check)"
      java_version_check() {
        original_java_version_check
        if [[ ${java_version%%.*} -ge 9 ]]; then
          java_args+=(
            --illegal-access=warn # set to deny if you feel brave
            --add-opens=java.base/java.nio=ALL-UNNAMED
            --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
            --add-opens=java.base/sun.security.util=ALL-UNNAMED
            --add-opens=java.base/sun.security.x509=ALL-UNNAMED
          )
        fi
      }
    """,
    /* Dockerization */
    dockerUsername := Some("casperlabs"),
    version in Docker := version.value +
      git.gitHeadCommit.value.fold("")("-git" + _.take(8)),
    dockerAliases ++=
      sys.env
        .get("DRONE_BUILD_NUMBER")
        .toSeq
        .map(num => dockerAlias.value.withTag(Some(s"DRONE-$num"))),
    dockerUpdateLatest := sys.env.get("DRONE").isEmpty,
    dockerBaseImage := "openjdk:11-jre-slim",
    dockerCommands := {
      Seq(
        Cmd("FROM", dockerBaseImage.value),
        ExecCmd("RUN", "apt", "clean"),
        ExecCmd("RUN", "apt", "update"),
        ExecCmd("RUN", "apt", "install", "-yq", "openssl", "curl"),
        Cmd("LABEL", s"""MAINTAINER="${maintainer.value}""""),
        Cmd("WORKDIR", (defaultLinuxInstallLocation in Docker).value),
        Cmd("ADD", "opt /opt"),
        Cmd("USER", "root"),
        ExecCmd("ENTRYPOINT", "bin/casperlabs-node"),
        ExecCmd("CMD", "run")
      )
    },
    /* Packaging */
    linuxPackageMappings ++= {
      val file = baseDirectory.value / "casperlabs-node.service"
      Seq(
        packageMapping(file -> "/lib/systemd/system/casperlabs-node.service")
      )
    },
    /* Debian */
    name in Debian := "casperlabs-node",
    debianPackageDependencies in Debian ++= Seq(
      "openjdk-11-jre-headless",
      "openssl(>= 1.0.2g) | openssl(>= 1.1.0f)", //ubuntu & debian
      "bash (>= 2.05a-11)"
    ),
    /* Redhat */
    rpmVendor := "casperlabs.io",
    rpmUrl := Some("https://casperlabs.io"),
    rpmLicense := Some("Apache 2.0"),
    packageArchitecture in Rpm := "noarch",
    maintainerScripts in Rpm := maintainerScriptsAppendFromFile((maintainerScripts in Rpm).value)(
      RpmConstants.Post -> (sourceDirectory.value / "rpm" / "scriptlets" / "post")
    ),
    rpmPrerequisites := Seq(
      /*
       * https://access.redhat.com/articles/1299013
       * Red Hat will skip Java SE 9 and 10, and ship an OpenJDK distribution based on Java SE 11.
       */
      "java-11-openjdk-headless >= 11.0.1.13",
      //"openssl >= 1.0.2k | openssl >= 1.1.0h", //centos & fedora but requires rpm 4.13 for boolean
      "openssl"
    ),
    rpmAutoreq := "no",
    Test / fork := true // Config tests errors would quit SBT itself due to Scallops.
  )
  .dependsOn(casper, comm, crypto)

lazy val storage = (project in file("storage"))
  .enablePlugins(JmhPlugin)
  .settings(commonSettings: _*)
  .settings(jmhSettings: _*)
  .settings(
    name := "storage",
    version := "0.0.1-SNAPSHOT",
    libraryDependencies ++= commonDependencies ++ protobufLibDependencies ++ Seq(
      lmdbjava,
      sqlLite,
      doobieCore,
      doobieHikari,
      flyway,
      catsCore,
      catsEffect,
      catsMtl
    ),
    PB.protoSources in Compile := Seq(protobufDirectory),
    includeFilter in PB.generate := new SimpleFileFilter(
      protobufSubDirectoryFilter(
        "io/casperlabs/storage"
      )
    ),
    PB.targets in Compile := Seq(
      scalapb.gen(flatPackage = true) -> (sourceManaged in Compile).value,
      grpcmonix.generators
        .GrpcMonixGenerator(flatPackage = true) -> (sourceManaged in Compile).value
    )
  )
  .dependsOn(shared, models % "compile->compile;test->test")

// Smart contract execution.
lazy val smartContracts = (project in file("smart-contracts"))
  .settings(commonSettings: _*)
  .settings(
    name := "smart-contracts",
    version := "0.0.1-SNAPSHOT",
    libraryDependencies ++= commonDependencies ++ protobufLibDependencies ++ Seq(
      nettyAll,
      grpcNetty,
      nettyTransNativeEpoll,
      nettyTransNativeKqueue
    ),
    PB.protoSources in Compile := Seq(protobufDirectory),
    includeFilter in PB.generate := new SimpleFileFilter(
      protobufSubDirectoryFilter(
        "io/casperlabs/ipc"
      )
    ),
    PB.targets in Compile := Seq(
      scalapb.gen(flatPackage = true) -> (sourceManaged in Compile).value,
      grpcmonix.generators
        .GrpcMonixGenerator(flatPackage = true) -> (sourceManaged in Compile).value
    )
  )
  .dependsOn(storage)

lazy val client = (project in file("client"))
  .enablePlugins(RpmPlugin, DebianPlugin, JavaAppPackaging, BuildInfoPlugin)
  .settings(commonSettings: _*)
  .settings(
    name := "client",
    version := nodeAndClientVersion,
    maintainer := "CasperLabs, LLC. <info@casperlabs.io>",
    packageName := "casperlabs-client",
    packageName in Docker := "client",
    executableScriptName := "casperlabs-client",
    javacOptions ++= Seq("-Dnashorn.args=\"--no-deprecation-warning\""),
    packageSummary := "CasperLabs Client",
    packageDescription := "CLI tool for interaction with the CasperLabs Node",
    libraryDependencies ++= commonDependencies ++ Seq(
      scallop,
      grpcNetty,
      graphvizJava,
      apacheCommons
    ),
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion, git.gitHeadCommit),
    buildInfoPackage := "io.casperlabs.client",
    /* Dockerization */
    dockerUsername := Some("casperlabs"),
    version in Docker := version.value +
      git.gitHeadCommit.value.fold("")("-git" + _.take(8)),
    dockerAliases ++=
      sys.env
        .get("DRONE_BUILD_NUMBER")
        .toSeq
        .map(num => dockerAlias.value.withTag(Some(s"DRONE-$num"))),
    dockerUpdateLatest := sys.env.get("DRONE").isEmpty,
    dockerBaseImage := "openjdk:11-jre-slim",
    dockerCommands := {
      Seq(
        Cmd("FROM", dockerBaseImage.value),
        Cmd("LABEL", s"""MAINTAINER="${maintainer.value}""""),
        Cmd("WORKDIR", (defaultLinuxInstallLocation in Docker).value),
        Cmd("ADD", "opt /opt"),
        Cmd("USER", "root"),
        ExecCmd("ENTRYPOINT", "bin/casperlabs-client"),
        ExecCmd("CMD", "run")
      )
    },
    /*
     * This monstrosity exists because
     * a) we want to get rid of annoying JVM >= 9 warnings,
     * b) we must support Java 8 for RedHat (see below) and
     * c) sbt-native-packager puts bashScriptExtraDefines before it
     *    initializes all useful variables (like $java_version).
     *
     * This won't work if someone passes -no-version-check command line
     * argument to casperlabs-node. They most probably know what they're doing.
     *
     * https://unix.stackexchange.com/a/29742/124070
     * Thanks Gilles!
     */
    bashScriptExtraDefines += """
      eval "original_$(declare -f java_version_check)"
      java_version_check() {
        original_java_version_check
        if [[ ${java_version%%.*} -ge 9 ]]; then
          java_args+=(
            --illegal-access=warn # set to deny if you feel brave
            --add-opens=java.base/java.nio=ALL-UNNAMED
            --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
            --add-opens=java.base/sun.security.util=ALL-UNNAMED
            --add-opens=java.base/sun.security.x509=ALL-UNNAMED
          )
        fi
      }
    """,
    /* Debian */
    name in Debian := "casperlabs-client",
    debianPackageDependencies in Debian ++= Seq(
      "openjdk-11-jre-headless",
      "openssl(>= 1.0.2g) | openssl(>= 1.1.0f)", //ubuntu & debian
      "bash (>= 2.05a-11)"
    ),
    /* Redhat */
    rpmVendor := "casperlabs.io",
    rpmUrl := Some("https://casperlabs.io"),
    rpmLicense := Some("Apache 2.0"),
    packageArchitecture in Rpm := "noarch",
    rpmPrerequisites := Seq(
      /*
       * https://access.redhat.com/articles/1299013
       * Red Hat will skip Java SE 9 and 10, and ship an OpenJDK distribution based on Java SE 11.
       */
      "java-11-openjdk-headless >= 11.0.1.13",
      //"openssl >= 1.0.2k | openssl >= 1.1.0h", //centos & fedora but requires rpm 4.13 for boolean
      "openssl"
    ),
    rpmAutoreq := "no",
    // Generate client stubs for the node API.
    PB.protoSources in Compile := Seq(protobufDirectory),
    includeFilter in PB.generate := new SimpleFileFilter(
      protobufSubDirectoryFilter(
        "google/api",
        "io/casperlabs/node/api"
      )
    ),
    // Generating into /protobuf because of a clash with sbt-buildinfo: https://github.com/thesamet/sbt-protoc/issues/8
    PB.targets in Compile := Seq(
      scalapb.gen(flatPackage = true) -> (sourceManaged in Compile).value / "protobuf",
      grpcmonix.generators
        .GrpcMonixGenerator(flatPackage = true) -> (sourceManaged in Compile).value / "protobuf"
    )
  )
  .dependsOn(crypto, shared, models, graphz)

lazy val benchmarks = (project in file("benchmarks"))
  .enablePlugins(RpmPlugin, DebianPlugin, JavaAppPackaging, BuildInfoPlugin)
  .settings(commonSettings: _*)
  .settings(
    name := "benchmarks",
    version := nodeAndClientVersion,
    maintainer := "CasperLabs, LLC. <info@casperlabs.io>",
    packageName := "casperlabs-benchmarks",
    packageName in Docker := "benchmarks",
    executableScriptName := "casperlabs-benchmarks",
    packageSummary := "CasperLabs Benchmarking CLI Client",
    packageDescription := "CLI tool for running benchmarks against the CasperLabs Node",
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion, git.gitHeadCommit),
    buildInfoPackage := "io.casperlabs.benchmarks",
    /* Dockerization */
    dockerUsername := Some("casperlabs"),
    version in Docker := version.value +
      git.gitHeadCommit.value.fold("")("-git" + _.take(8)),
    dockerAliases ++=
      sys.env
        .get("DRONE_BUILD_NUMBER")
        .toSeq
        .map(num => dockerAlias.value.withTag(Some(s"DRONE-$num"))),
    dockerUpdateLatest := sys.env.get("DRONE").isEmpty,
    dockerBaseImage := "openjdk:11-jre-slim",
    dockerCommands := {
      Seq(
        Cmd("FROM", dockerBaseImage.value),
        Cmd("LABEL", s"""MAINTAINER="${maintainer.value}""""),
        Cmd("WORKDIR", (defaultLinuxInstallLocation in Docker).value),
        Cmd("ADD", "opt /opt"),
        Cmd("USER", "root"),
        ExecCmd("ENTRYPOINT", "bin/casperlabs-benchmarks"),
        ExecCmd("CMD", "run")
      )
    },
    libraryDependencies ++= commonDependencies
  )
  .dependsOn(client)

/**
  * This project contains Gatling test suits which perform load testing.
  * It could be run with `sbt "project gatling" gatling:test`.
  */
lazy val gatling = (project in file("gatling"))
  .enablePlugins(GatlingPlugin)
  .settings(
    libraryDependencies ++= gatlingDependencies,
    dependencyOverrides ++= gatlingOverrides,
    PB.protoSources in Compile := Seq(protobufDirectory),
    includeFilter in PB.generate := new SimpleFileFilter(
      protobufSubDirectoryFilter(
        "io/casperlabs/comm/discovery"
      )
    ),
    // Generating into /protobuf because of https://github.com/thesamet/sbt-protoc/issues/8
    PB.targets in Compile := Seq(
      scalapb.gen(flatPackage = true) -> (sourceManaged in Compile).value / "protobuf",
      grpcmonix.generators
        .GrpcMonixGenerator(flatPackage = true) -> (sourceManaged in Compile).value / "protobuf",
      PB.gens.java                              -> (sourceManaged in Compile).value / "protobuf"
    )
  )
  .dependsOn(shared)

lazy val casperlabs = (project in file("."))
  .settings(commonSettings: _*)
  .aggregate(
    storage,
    casper,
    comm,
    crypto,
    graphz,
    models,
    node,
    shared,
    smartContracts,
    client,
    benchmarks
  )
