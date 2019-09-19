package io.casperlabs.node.configuration

import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNel
import com.google.protobuf.ByteString
import io.casperlabs.crypto.codec.Base64
import io.casperlabs.ipc
import java.io.File
import org.scalatest._
import scala.io.Source

class ChainSpecTest extends FlatSpec with Matchers {

  behavior of "ChainSpec"

  it should "read the InitialConf from a directory" in {
    val manifest = Source.fromResource("chainspec/0-genesis/manifest.toml")

    check(ChainSpec.InitialConf.parse(manifest)) { initialConf =>
      initialConf.genesis.name shouldBe "test-chain"
      initialConf.genesis.timestamp shouldBe 1568805354071L
      initialConf.genesis.mintCodePath.toString shouldBe "mint.wasm"
      initialConf.wasmCosts.regular.value shouldBe 1
      initialConf.wasmCosts.memInitialPages.value shouldBe 5
      initialConf.wasmCosts.opcodesDivisor.value shouldBe 10
    }
  }

  it should "read the genesis ChainSpec form a directory" in new ChainSpecReader {
    check(
      ipc.ChainSpec.fromDirectory(new File("src/test/resources/chainspec").toPath)
    ) { spec =>
      spec.genesis should not be empty
      spec.upgrades shouldBe empty

      val genesis = spec.getGenesis
      genesis.name shouldBe "test-chain"
      genesis.timestamp shouldBe 1568805354071L
      genesis.getProtocolVersion.value shouldBe 1L

      new String(genesis.mintInstaller.toByteArray).trim shouldBe "mint contract bytes"
      new String(genesis.posInstaller.toByteArray).trim shouldBe "pos contract bytes"

      val accounts = genesis.accounts
      accounts should have size 4
      accounts(0).publicKey shouldBe ByteString.copyFrom(
        Base64.tryDecode("o8C2vZUXgaDKX3pfXmSJxeNfkHueLMrgiP1wIbSYHvo=").get
      )
      accounts(0).getBalance.value shouldBe "0"
      accounts(0).getBalance.bitWidth shouldBe 512
      accounts(0).getBondedAmount.value shouldBe "100"
      accounts(0).getBondedAmount.bitWidth shouldBe 512
      accounts(3).getBalance.value shouldBe "2"

      val wasmCosts = genesis.getCosts.getWasm
      wasmCosts.regular shouldBe 1
      wasmCosts.div shouldBe 2
      wasmCosts.mul shouldBe 3
      wasmCosts.mem shouldBe 4
      wasmCosts.initialMem shouldBe 5
      wasmCosts.growMem shouldBe 6
      wasmCosts.memcpy shouldBe 7
      wasmCosts.maxStackHeight shouldBe 8
      wasmCosts.opcodesMul shouldBe 9
      wasmCosts.opcodesDiv shouldBe 10
    }
  }

  def check[A](value: ValidatedNel[String, A])(test: A => Unit) =
    value match {
      case Invalid(errors) =>
        fail(errors.toList.mkString(" "))
      case Valid(x) =>
        test(x)
    }
}
