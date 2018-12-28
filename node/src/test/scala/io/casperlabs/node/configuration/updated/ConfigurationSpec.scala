package io.casperlabs.node.configuration.updated

import java.nio.charset.Charset
import java.nio.file.{Files, OpenOption, Paths, StandardOpenOption}

import org.scalatest._

class ConfigurationSpec extends FunSuite with Matchers {

  test("Configuration.Diagnostics should fallback field-by-field") {
    val configOne   = Configuration.Diagnostics(Some("test"), None, None)
    val configTwo   = Configuration.Diagnostics(None, Some(1), None)
    val configThree = Configuration.Diagnostics(None, None, Some(2))

    val Right(result) =
      configOne.fallbackTo(configTwo).flatMap(_.fallbackTo(configThree))
    result.grpcHost shouldEqual Some("test")
    result.grpcPort shouldEqual Some(1)
    result.grpcPortInternal shouldEqual Some(2)
  }

  test("""
      |Configuration should properly parse arguments
      |with fallbacking to --config-file and the 'default-configuration.toml' from resources
    """.stripMargin) {
    val configFilename = "test-configuration.toml"
    Files.write(
      Paths.get(configFilename),
      """
        |[diagnostics]
        |grpc-port = 10
      """.stripMargin.getBytes(Charset.forName("UTF-8")),
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE
    )
    val args =
      Array(s"--config-file=$configFilename", "--grpc-port-internal=500", "diagnostics")
    val Right(c: Configuration.Diagnostics) = Configuration.parse(args)
    c.grpcHost shouldEqual Some("localhost")
    c.grpcPort shouldEqual Some(10)
    c.grpcPortInternal shouldEqual Some(500)

    Files.delete(Paths.get(configFilename))
  }
}
