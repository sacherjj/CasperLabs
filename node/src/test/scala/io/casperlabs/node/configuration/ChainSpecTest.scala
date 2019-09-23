package io.casperlabs.node.configuration

import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNel
import com.google.protobuf.ByteString
import io.casperlabs.crypto.codec.Base64
import io.casperlabs.ipc
import java.io.File
import java.nio.file.Paths
import org.scalatest._
import scala.io.Source

class ChainSpecTest extends WordSpecLike with Matchers with Inspectors with ChainSpecReader {

  "GenesisConf" should {
    "parse a manifest file" in {
      val manifest = Source.fromResource("chainspec/1-genesis/manifest.toml")

      check(ChainSpec.GenesisConf.parseManifest(manifest)) { conf =>
        conf.genesis.protocolVersion shouldBe 1L
        conf.genesis.name shouldBe "test-chain"
        conf.genesis.timestamp shouldBe 1568805354071L
        conf.genesis.mintCodePath.toString shouldBe "mint.wasm"
        conf.wasmCosts.regular.value shouldBe 1
        conf.wasmCosts.memInitialPages.value shouldBe 5
        conf.wasmCosts.opcodesDivisor.value shouldBe 10
      }
    }

    "not parse a manifest with missing fields" in {
      val manifest = Source.fromResource("chainspec-invalids/genesis-with-missing-fields.toml")

      checkInvalid(ChainSpec.GenesisConf.parseManifest(manifest)) { errors =>
        forExactly(1, errors) {
          _ should (include("regular") and include("must be defined"))
        }
        forExactly(1, errors) {
          _ should (include("timestamp") and include("must be defined"))
        }
      }
    }
  }

  "UpgradeConf" should {
    "parse a manifest file with costs" in {
      val manifest = Source.fromResource("chainspec/2-upgrade/manifest.toml")

      check(ChainSpec.UpgradeConf.parseManifest(manifest)) { conf =>
        conf.upgrade.protocolVersion shouldBe 2L
        conf.upgrade.activationPointRank shouldBe 20L
        conf.upgrade.installerCodePath.get.toString shouldBe "installer.wasm"
        conf.wasmCosts should not be empty
        conf.wasmCosts.get.regular.value shouldBe 21
        conf.wasmCosts.get.memInitialPages.value shouldBe 25
        conf.wasmCosts.get.opcodesDivisor.value shouldBe 210
      }
    }

    "parse a manifest file without costs" in {
      val manifest = Source.fromResource("chainspec/3-upgrade/manifest.toml")

      check(ChainSpec.UpgradeConf.parseManifest(manifest)) { conf =>
        conf.upgrade.activationPointRank shouldBe 30L
        conf.upgrade.protocolVersion shouldBe 3L
        conf.upgrade.installerCodePath shouldBe empty
        conf.wasmCosts shouldBe empty
      }
    }

    "not parse a manifest with missing or partial costs" in {
      val manifest = Source.fromResource("chainspec-invalids/upgrade-with-missing-fields.toml")

      checkInvalid(ChainSpec.UpgradeConf.parseManifest(manifest)) { errors =>
        forExactly(1, errors) {
          _ should (include("activationPointRank") and include("must be defined"))
        }
        forExactly(1, errors) {
          _ should (include("maxStackHeight") and include("must be defined"))
        }
      }
    }
  }

  "ChainSpec" when {
    "reading a valid directory" should {
      val readSpec = ipc.ChainSpec.fromDirectory(new File("src/test/resources/chainspec").toPath)

      "read Genesis" in {
        check(readSpec) { spec =>
          spec.genesis should not be empty
          spec.upgrades should have size 2

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

      "read an upgrade which contains installer and costs" in {
        check(readSpec) { spec =>
          spec.upgrades.size should be >= 1

          val upgrade = spec.upgrades(0)
          upgrade.getActivationPoint.rank shouldBe 20L
          upgrade.getProtocolVersion.value shouldBe 2L

          new String(upgrade.getUpgradeInstaller.code.toByteArray).trim shouldBe "installer contract bytes"

          val wasmCosts = upgrade.getNewCosts.getWasm
          wasmCosts.regular shouldBe 21
          wasmCosts.div shouldBe 22
          wasmCosts.mul shouldBe 23
          wasmCosts.mem shouldBe 24
          wasmCosts.initialMem shouldBe 25
          wasmCosts.growMem shouldBe 26
          wasmCosts.memcpy shouldBe 27
          wasmCosts.maxStackHeight shouldBe 28
          wasmCosts.opcodesMul shouldBe 29
          wasmCosts.opcodesDiv shouldBe 210
        }
      }

      "read an upgrade which has no installer and no new costs" in {
        check(readSpec) { spec =>
          spec.upgrades.size should be >= 2

          val upgrade = spec.upgrades(1)
          upgrade.getActivationPoint.rank shouldBe 30L
          upgrade.getProtocolVersion.value shouldBe 3L

          upgrade.upgradeInstaller shouldBe empty

          upgrade.newCosts shouldBe empty
        }
      }
    }
  }

  "resolvePath" should {
    "handle relative paths" in {
      ChainSpec.resolvePath(Paths.get("a/b"), Paths.get("c.wasm")) shouldBe Paths.get("a/b/c.wasm")
    }
    "handle absolute paths" in {
      ChainSpec.resolvePath(Paths.get("a/b"), Paths.get("/d/c.wasm")) shouldBe Paths.get(
        "/d/c.wasm"
      )
    }
    "handle paths based on home directory" in {
      val path = ChainSpec.resolvePath(Paths.get("a/b"), Paths.get("~/d/c.wasm")).toString
      path should not startWith ("~")
      path should not startWith ("a/b")
      path should endWith("/d/c.wasm")
    }
  }

  def check[A](value: ValidatedNel[String, A])(test: A => Unit) =
    value match {
      case Invalid(errors) =>
        fail(errors.toList.mkString(" "))
      case Valid(x) =>
        test(x)
    }

  def checkInvalid[A](value: ValidatedNel[String, _])(test: List[String] => Unit) =
    value match {
      case Invalid(errors) =>
        test(errors.toList)
      case Valid(_) =>
        fail("Expected the value to be invalid.")
    }
}
