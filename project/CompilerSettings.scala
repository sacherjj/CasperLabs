import java.lang.Runtime.getRuntime

import sbt._
import sbt.Keys._

object CompilerSettings {

  /*
   * In the future, let's add:
   *
   *   "-Xfatal-warnings",
   *   "-Xlint:adapted-args",
   *   "-Xlint:inaccessible",
   *   "-Ywarn-value-discard",
   */

  lazy val options = Seq(
    javacOptions ++= Seq("-encoding", "UTF-8"),
    scalacOptions ++= Seq(
      // format: off
      "-Xfuture",
      "-Ypartial-unification",
      "-Ywarn-dead-code",
      "-Ywarn-numeric-widen",
      "-deprecation",
      "-encoding", "UTF-8",
      "-feature",
      "-language:_",
      "-unchecked",
      //With > 16: [error] invalid setting for -Ybackend-parallelism must be between 1 and 16
      //https://github.com/scala/scala/blob/v2.12.6/src/compiler/scala/tools/nsc/settings/ScalaSettings.scala#L240
      "-Ybackend-parallelism", getRuntime.availableProcessors().min(16).toString,
      "-Xlint:-unused,-adapted-args,-inaccessible,_",
      "-Ywarn-unused:implicits",
      "-Ywarn-macros:after",
      "-Ywarn-unused:locals",
      "-Ywarn-unused:patvars",
      "-Ywarn-unused:privates"
      // format: on
    ),
    scalacOptions in (Compile, console) ~= {
      _.filterNot(
        Set(
          "-Xfatal-warnings",
          "-Ywarn-unused-import",
          "-Ywarn-unused:imports"
        )
      )
    },
    scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value
  )
}
