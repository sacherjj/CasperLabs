import Dependencies._
import com.typesafe.sbt.packager.docker._

//allow stopping sbt tasks using ctrl+c without killing sbt itself
Global / cancelable := true

//disallow any unresolved version conflicts at all for faster feedback
Global / conflictManager := ConflictManager.strict
//resolve all version conflicts explicitly
Global / dependencyOverrides := Dependencies.overrides

lazy val projectSettings = Seq(
  organization := "io.casperlabs",
  scalaVersion := "2.12.7",
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
  Test / fork := true,
  Test / parallelExecution := false,
  Test / testForkedParallel := false,
  IntegrationTest / fork := true,
  IntegrationTest / parallelExecution := false,
  IntegrationTest / testForkedParallel := false
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

lazy val shared = (project in file("shared"))
  .settings(commonSettings: _*)
  .settings(
    version := "0.1",
    libraryDependencies ++= commonDependencies ++ Seq(
      catsCore,
      catsEffect,
      catsMtl,
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
  ).dependsOn(shared)

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
    blockStorage % "compile->compile;test->test",
    comm         % "compile->compile;test->test",
    shared       % "compile->compile;test->test",
		graphz,
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
      guava
    ),
    PB.targets in Compile := Seq(
      PB.gens.java                              -> (sourceManaged in Compile).value,
      scalapb.gen(javaConversions = true)       -> (sourceManaged in Compile).value,
      grpcmonix.generators.GrpcMonixGenerator() -> (sourceManaged in Compile).value
    )
  )
  .dependsOn(shared % "compile->compile;test->test", crypto, smartContracts)

lazy val crypto = (project in file("crypto"))
  .settings(commonSettings: _*)
  .settings(
    name := "crypto",
    libraryDependencies ++= commonDependencies ++ protobufLibDependencies ++ Seq(
      guava,
      bouncyCastle,
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
      scalacheckShapeless,
      scalapbRuntimegGrpc
    ),
    PB.targets in Compile := Seq(
      scalapb.gen(flatPackage = true) -> (sourceManaged in Compile).value,
      grpcmonix.generators
        .GrpcMonixGenerator(flatPackage = true) -> (sourceManaged in Compile).value
    )
  )
  .dependsOn(crypto, shared % "compile->compile;test->test")

val nodeAndClientVersion = "0.0"

lazy val node = (project in file("node"))
  .settings(commonSettings: _*)
  .enablePlugins(RpmPlugin, DebianPlugin, JavaAppPackaging, BuildInfoPlugin)
  .settings(
    version := nodeAndClientVersion,
    name := "node",
    maintainer := "CasperLabs, LLC. <info@casperlabs.io>",
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
        tomlScala
      ),
    PB.targets in Compile := Seq(
      PB.gens.java                              -> (sourceManaged in Compile).value / "protobuf",
      scalapb.gen(javaConversions = true)       -> (sourceManaged in Compile).value / "protobuf",
      grpcmonix.generators.GrpcMonixGenerator() -> (sourceManaged in Compile).value / "protobuf"
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
     * argument to casperlabsnode. They most probably know what they're doing.
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
    dockerUsername := Some(organization.value),
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
      val daemon = (daemonUser in Docker).value
      Seq(
        Cmd("FROM", dockerBaseImage.value),
        ExecCmd("RUN", "apt", "update"),
        ExecCmd("RUN", "apt", "install", "-yq", "openssl"),
        Cmd("LABEL", s"""MAINTAINER="${maintainer.value}""""),
        Cmd("WORKDIR", (defaultLinuxInstallLocation in Docker).value),
        Cmd("ADD", s"--chown=$daemon:$daemon opt /opt"),
        Cmd("USER", "root"),
        ExecCmd("ENTRYPOINT", "bin/node"),
        ExecCmd("CMD", "run")
      )
    },
    /* Packaging */
    linuxPackageMappings ++= {
      val file = baseDirectory.value / "casperlabsnode.service"
      Seq(
        packageMapping(file -> "/lib/systemd/system/casperlabsnode.service")
      )
    },
    /* Debian */
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
      "java-1.8.0-openjdk-headless >= 1.8.0.171",
      //"openssl >= 1.0.2k | openssl >= 1.1.0h", //centos & fedora but requires rpm 4.13 for boolean
      "openssl"
    )
  )
  .dependsOn(casper, comm, crypto, smartContracts)

lazy val blockStorage = (project in file("block-storage"))
  .settings(commonSettings: _*)
  .settings(
    name := "block-storage",
    version := "0.0.1-SNAPSHOT",
    libraryDependencies ++= commonDependencies ++ protobufLibDependencies ++ Seq(
      lmdbjava,
      catsCore,
      catsEffect,
      catsMtl
    )
  )
  .dependsOn(shared, models % "compile->compile;test->test")

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
    )
  )
  .dependsOn(shared, models)

lazy val client = (project in file("client"))
  .enablePlugins(RpmPlugin, DebianPlugin, JavaAppPackaging, BuildInfoPlugin)
  .settings(commonSettings: _*)
  .settings(
    name := "client",
    version := nodeAndClientVersion,
    maintainer := "CasperLabs, LLC. <info@casperlabs.io>",
    packageSummary := "CasperLabs Client",
    packageDescription := "CasperLabs Client - the client for interaction with the CasperLabs Node server software.",
    libraryDependencies ++= commonDependencies ++ Seq(scallop, grpcNetty),
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion, git.gitHeadCommit),
    buildInfoPackage := "io.casperlabs.client",
    /* Dockerization */
    dockerUsername := Some(organization.value),
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
      val daemon = (daemonUser in Docker).value
      Seq(
        Cmd("FROM", dockerBaseImage.value),
        ExecCmd("RUN", "apt", "update"),
        ExecCmd("RUN", "apt", "install", "-yq", "openssl"),
        Cmd("LABEL", s"""MAINTAINER="${maintainer.value}""""),
        Cmd("WORKDIR", (defaultLinuxInstallLocation in Docker).value),
        Cmd("ADD", s"--chown=$daemon:$daemon opt /opt"),
        Cmd("USER", "root"),
        ExecCmd("ENTRYPOINT", "bin/client"),
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
     * argument to casperlabsnode. They most probably know what they're doing.
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
      "java-1.8.0-openjdk-headless >= 1.8.0.171",
      //"openssl >= 1.0.2k | openssl >= 1.1.0h", //centos & fedora but requires rpm 4.13 for boolean
      "openssl"
    ),
    rpmAutoreq := "no"
  )
  .dependsOn(shared, models)

lazy val casperlabs = (project in file("."))
  .settings(commonSettings: _*)
  .aggregate(
    blockStorage,
    casper,
    comm,
    crypto,
    graphz,
    models,
    node,
    shared,
    smartContracts
  )
