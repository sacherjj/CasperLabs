package io.casperlabs.shared

import scala.annotation.{compileTimeOnly, StaticAnnotation}
import scala.language.experimental.macros
import scala.reflect.macros.blackbox

/**
  * Generates scallop options
  * ==Example==
  * {{{
  * val defaults: Map[String, String] = Map("someOption" -> "10")
  * var fields: Map[(ScallopConfBase, String), () => ScallopOption[String]]
  * def gen[A](descr: String, short: Char = '\u0000'): ScallopOption[A] =
  *   sys.error("Add @scallop macro annotation")
  *
  * val run = new Subcommand {
  *   @scallop
  *   val someOption = gen[Int]("Some description.", 'c')
  *   @scallop
  *   val anotherOption = gen[String]("Another description.")
  * }
  * }}}
  * Will produce:
  * {{{
  * val run = new Subcommand {
  *   val someOption = {
  *     fields += ((this, "someOption"), () => someOption.map(_.toString))
  *     opt[Int](descr = "Int. Some description. Default is 10.", short = 'c')
  *   }
  *
  *   val anotherOption = {
  *     fields += ((this, "anotherOption"), () => anotherOption.map(_.toString))
  *     opt[Int](descr = "Int. Some description. Default is 10.", short = 'c')
  *   }
  * }
  * }}}
  */
@compileTimeOnly("enable macro paradise")
class scallop extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro scallopImpl.impl
}

object scallopImpl {
  def impl(c: blackbox.Context)(annottees: c.Tree*): c.Tree = {
    import c.universe._
    annottees.head match {
      case q"val $term = gen[$tpe]($descr, $short)" =>
        val termName       = termNameAsString(c)(term)
        val tomlType       = tomlTypeDefinition(c)(tpe)
        val default        = defaultDefinition(c)(termName)
        val puttingIntoMap = putIntoMap(c)(term, termName)
        optionDefinitionWithShort(c)(
          term,
          termName,
          tpe,
          tomlType,
          default,
          puttingIntoMap,
          descr,
          short
        )
      case q"val $term = gen[$tpe]($descr)" =>
        val termName       = termNameAsString(c)(term)
        val tomlType       = tomlTypeDefinition(c)(tpe)
        val default        = defaultDefinition(c)(termName)
        val puttingIntoMap = putIntoMap(c)(term, termName)
        optionDefinition(c)(term, termName, tpe, tomlType, puttingIntoMap, default, descr)
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

  private def optionDefinition(
      c: blackbox.Context
  )(
      term: c.universe.TermName,
      termName: String,
      tpe: c.Tree,
      tomlType: c.Tree,
      puttingIntoMap: c.Tree,
      default: c.Tree,
      descr: c.Tree
  ) = {
    import c.universe._
    q"""
      val $term: ScallopOption[$tpe] = {
        $tomlType;
        $default;
        $puttingIntoMap;
        opt[$tpe](descr = s"$$tomlType. " + $descr + s"$$default")
      };
      """
  }

  private def optionDefinitionWithShort(c: blackbox.Context)(
      term: c.universe.TermName,
      termName: String,
      tpe: c.Tree,
      tomlType: c.Tree,
      default: c.Tree,
      puttingIntoMap: c.Tree,
      descr: c.Tree,
      short: c.Tree
  ) = {
    import c.universe._
    q"""
      val $term: ScallopOption[$tpe] = {
        $tomlType;
        $default;
        $puttingIntoMap;
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

  /*Value is lambda because of macroses limitation generating all code wrapped into {...}*/
  private def putIntoMap(c: blackbox.Context)(term: c.universe.TermName, termName: String) = {
    import c.universe._
    q"fields += ((this, $termName), () => $term.map(_.toString));"
  }
}
