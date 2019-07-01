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
      "-deprecation",
      "-encoding",
      "UTF-8",
      "-feature",
      "-language:_",
      "-unchecked",
      "-Xfuture",
      "-Xfatal-warnings",
      "-Xcheckinit",
      "-Xlint:adapted-args",
      "-Xlint:nullary-unit",
      "-Xlint:inaccessible",
      "-Xlint:nullary-override",
      "-Xlint:infer-any",
      "-Xlint:missing-interpolator",
      "-Xlint:private-shadow",
      "-Xlint:type-parameter-shadow",
      "-Xlint:poly-implicit-overload",
      "-Xlint:option-implicit",
      "-Xlint:delayedinit-select",
      "-Xlint:by-name-right-associative",
      "-Xlint:package-object-classes",
      "-Xlint:unsound-match",
      "-Xlint:stars-align",
      "-Xlint:constant",
      "-Yno-adapted-args",
      "-Ypartial-unification",
      "-Ywarn-macros:both",
      "-Ywarn-dead-code",
      "-Ywarn-numeric-widen",
      "-Ywarn-unused:patvars",
      "-Ywarn-unused:privates",
      "-Ywarn-unused:locals",
      "-Ywarn-unused:explicits",
      "-Ywarn-unused:implicits",
      "-Ywarn-extra-implicit",
      //With > 16: [error] invalid setting for -Ybackend-parallelism must be between 1 and 16
      //https://github.com/scala/scala/blob/v2.12.6/src/compiler/scala/tools/nsc/settings/ScalaSettings.scala#L240
      "-Ybackend-parallelism",
      getRuntime.availableProcessors().min(16).toString
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
