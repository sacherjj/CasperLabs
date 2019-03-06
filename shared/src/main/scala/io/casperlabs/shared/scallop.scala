package io.casperlabs.shared

import scala.annotation.{compileTimeOnly, StaticAnnotation}
import scala.language.experimental.macros
import scala.reflect.macros.blackbox

/**
  * Generates scallop options
  * ==Example==
  * {{{
  * val defaults: Map[String, String] = Map("someOption" -> "10")
  * def gen[A](descr: String, short: Char = '\u0000'): ScallopOption[A] =
  *   sys.error("Add @scallop macro annotation")
  *
  * @scallop
  * val someOption = gen[Int]("Some description.", 'c')
  * @scallop
  * val anotherOption = gen[String]("Another description.")
  * }}}
  * Will produce:
  * {{{
  * val someOption = opt[Int](descr = "Int. Some description. Default is 10.", short = 'c')
  * val anotherOption = opt[String](descr = "String. Another description.")
  * }}}
  *
  */
@compileTimeOnly("enable macro paradise")
class scallop extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro scallopImpl.impl
}

object scallopImpl {
  def impl(c: blackbox.Context)(annottees: c.Tree*): c.Tree = {
    import c.universe._
    annottees.head match {
      case q"val $variableName = gen[$tpe]($descr, $short)" =>
        val termName = termNameAsString(c)(variableName)
        val tomlType = tomlTypeDefinition(c)(tpe)
        val default  = defaultDefinition(c)(termName)
        optionWithShort(c)(variableName, tpe, tomlType, default, descr, short)
      case q"val $variableName = gen[$tpe]($descr)" =>
        val termName = termNameAsString(c)(variableName)
        val tomlType = tomlTypeDefinition(c)(tpe)
        val default  = defaultDefinition(c)(termName)
        option(c)(variableName, tpe, tomlType, default, descr)
    }
  }
  private def tomlTypeDefinition(c: blackbox.Context)(tpe: c.Tree) = {
    import c.universe._

    val t = tpe match {
      case Ident(TypeName("Flag")) => Ident(TypeName("Boolean"))
      case x                       => x
    }

    q"""
          val tomlType: String = classOf[$t].getSimpleName match {
            case "Path" | "String" | "PeerNode" | "StoreType" | "FiniteDuration" => "String"
            case "int" | "long"                                                  => "Integer"
            case "boolean"                                                       => "Boolean"
          }"""
  }

  private def defaultDefinition(c: blackbox.Context)(termName: String) = {
    import c.universe._
    q"""val default: String = defaults.get($termName).fold("")(s => " Default: " + s)"""
  }

  private def option(
      c: blackbox.Context
  )(term: c.universe.TermName, tpe: c.Tree, tomlType: c.Tree, default: c.Tree, descr: c.Tree) = {
    import c.universe._
    q"""
      val $term: ScallopOption[$tpe] = {
        $tomlType;
        $default;
        opt[$tpe](descr = s"$$tomlType. " + $descr + s"$$default")
      };
      """
  }

  private def optionWithShort(c: blackbox.Context)(
      term: c.universe.TermName,
      tpe: c.Tree,
      tomlType: c.Tree,
      default: c.Tree,
      descr: c.Tree,
      short: c.Tree
  ) = {
    import c.universe._
    q"""
      val $term: ScallopOption[$tpe] = {
        $tomlType;
        $default;
        opt[$tpe](descr = s"$$tomlType. " + $descr + s"$$default", short = $short)
      };
      """
  }

  private def termNameAsString(c: blackbox.Context)(variableName: c.universe.TermName) = {
    import c.universe._
    variableName match {
      case TermName(name) =>
        name
    }
  }
}
