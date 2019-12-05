package io.casperlabs.casper.util

import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.ipc.ChainSpec
import org.scalatest.{Matchers, WordSpec}

class CasperLabsProtocolSpec extends WordSpec with Matchers {

  "CasperLabsProtocol" when {
    val genesisDeployConfig = ChainSpec.DeployConfig(1, 1)
    val genesis             = (0L, ProtocolVersion(1, 0, 0), Option(genesisDeployConfig))
    "asked for configuration that is in the latest upgrade config" should {
      val deployConfigA = ChainSpec.DeployConfig(100, 100)
      // Deploy config is being changed in the upgradeA.
      // Should overwrite configuration from Genesis.
      val upgradeA   = (1L, ProtocolVersion(1, 1, 0), Option(deployConfigA))
      val clProtocol = CasperLabsProtocol.unsafe(Seq(genesis, upgradeA): _*)

      "return configuration from that config" in {
        clProtocol.configAt(upgradeA._1).deployConfig should be(deployConfigA)
      }
    }

    "asked for configuration absent in the latest upgrade config" should {
      val upgradeA = (1L, ProtocolVersion(1, 1, 0), None)
      val upgradeB = (2L, ProtocolVersion(1, 2, 0), None)
      // Last deploy config is in the Genesis; neither upgradeA nor upgradeB changes it.
      val clProtocol = CasperLabsProtocol.unsafe(Seq(genesis, upgradeA, upgradeB): _*)

      "fallback to the latest available configuration" in {
        clProtocol.configAt(upgradeB._1).deployConfig should be(genesisDeployConfig)
      }
    }
  }

}
