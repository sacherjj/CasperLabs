package io.casperlabs.node.configuration

import cats.data.Validated.{Invalid, Valid}
import org.scalatest._
import scala.io.Source

class ChainSpecTest extends FlatSpec with Matchers {

  behavior of "ChainSpec"

  it should "read the InitialConf from a directory" in {
    val manifest = Source.fromResource("chainspec/0-genesis/manifest.toml")

    val initialConf = ChainSpec.InitialConf.parse(manifest)

    initialConf match {
      case Invalid(errors) =>
        fail(errors.toList.mkString(" "))
      case Valid(conf) =>
        conf.genesis.name shouldBe "test-chain"
        conf.genesis.timestamp shouldBe 1568805354071L
        conf.genesis.mintCodePath.toString shouldBe "mint.wasm"
        conf.wasmCosts.regular.value shouldBe 1
        conf.wasmCosts.memInitialPages.value shouldBe 5
        conf.wasmCosts.opcodesDivisor.value shouldBe 10
    }
  }
}
