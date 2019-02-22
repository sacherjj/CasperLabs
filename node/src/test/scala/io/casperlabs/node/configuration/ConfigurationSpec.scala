package io.casperlabs.node.configuration
import java.nio.file.{Path, Paths}

import org.scalatest.FunSuite

import scala.reflect.ClassTag

class ConfigurationSpec extends FunSuite {

  test("""|Configuration.parseToActual should
          |respect server.dataDir changes for 'Path' options""".stripMargin) {
    def updateDataDir(c: ConfigurationSoft, dataDir: Path): ConfigurationSoft =
      c.copy(server = c.server.copy(dataDir = Option(dataDir)))

    for {
      defaultConfSoft <- ConfigurationSoft.tryDefault.toOption
      confSoft        = updateDataDir(defaultConfSoft, Paths.get("/tmp/casperlabs-data-dir"))
      conf <- Configuration
               .parseToActual(Configuration.Command.Run, defaultConfSoft, confSoft)
               .toOption
    } {
      //List(server.dataDir -> some path, ..., tls.certificate -> some path ...)
      val paths = extractPathsWithKeys(conf)

      val invalidPaths = paths
        .filterNot {
          case (_, path) =>
            path.startsWith(Paths.get("/tmp/casperlabs-data-dir"))
        }
        .map(_._1)

      assert(invalidPaths.isEmpty, "Invalid paths, wrap them into 'Configuration#adjustPath'")
    }
  }

  private def extractPathsWithKeys(conf: Configuration): List[(String, Path)] = {
    import shapeless._
    import ops.record._

    val keys: List[String] = {
      val gen = LabelledGeneric[Configuration]
      /*_*/
      Keys[gen.Repr].apply().toList.map(_.name)
      /*_*/
    }

    val gen   = Generic[Configuration]
    val hlist = gen.to(conf)

    object mapper extends Poly1 {
      implicit def genCase[A <: Product: ClassTag] = at[A] { a =>
        import scala.reflect.classTag
        classTag[A].runtimeClass.getDeclaredFields
          .filterNot(_.isSynthetic)
          .map(_.getName)
          .zip(a.productIterator.toSeq)
          .filter {
            case (_, v) => v.isInstanceOf[java.nio.file.Path]
          }
          .map {
            case (k, v) => (k, v.asInstanceOf[java.nio.file.Path])
          }
          .toList
      }
    }

    val paths: List[List[(String, java.nio.file.Path)]] = hlist.map(mapper).toList
    keys.zip(paths).flatMap {
      case (baseFieldName, childrenFields) =>
        childrenFields.map {
          case (childrenFieldName, field) => (s"$baseFieldName.$childrenFieldName", field)
        }
    }
  }
}
