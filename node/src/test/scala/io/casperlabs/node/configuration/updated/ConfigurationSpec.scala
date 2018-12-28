package io.casperlabs.node.configuration.updated

import org.scalatest._

class ConfigurationSpec extends FunSuite with Matchers {

  test("Configuration.Diagnostics should fallback field-by-field") {
    val configOne   = Configuration.Diagnostics(Some("test"), None, None)
    val configTwo   = Configuration.Diagnostics(None, Some(1), None)
    val configThree = Configuration.Diagnostics(None, None, Some(2))

    val Some(result: Configuration.Diagnostics) =
      configOne.fallbackTo(configTwo).flatMap(_.fallbackTo(configThree))
    assert(result.grpcHost.contains("test"))
    assert(result.grpcPort.contains(1))
    assert(result.grpcPortInternal.contains(2))
  }
}
