package io.casperlabs.casper.util

import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.casper.util.ProtocolVersions.{Config}
import io.casperlabs.ipc.ChainSpec.DeployConfig
import org.scalatest.{Assertion, Inspectors, Matchers, WordSpec}

class ProtocolVersionsTest extends WordSpec with Matchers with Inspectors {

  val deployConfig = DeployConfig(
    maxTtlMillis = 24 * 60 * 60 * 1000, // 1 day
    maxDependencies = 10
  )

  def compareErrorMessages(error: AssertionError, expected: String): Assertion =
    error.getMessage should equal("assertion failed: " + expected)

  def semver(version: String) = {
    val Array(major, minor, patch) = version.split('.')
    ProtocolVersion(major.toInt, minor.toInt, patch.toInt)
  }

  "ProtocolVersion" when {
    "created from empty list" should {
      "throw an assertion error" in {
        val thrown = the[java.lang.IllegalArgumentException] thrownBy {
          ProtocolVersions(List())
        }
        thrown.getMessage should equal("requirement failed: List cannot be empty.")
      }
    }
    "created with lower bound different than 0" should {
      "throw an assertion error" in {
        val thrown = the[java.lang.IllegalArgumentException] thrownBy {
          ProtocolVersions(List(Config(1, ProtocolVersion(1), deployConfig)))
        }
        thrown.getMessage should equal(
          "requirement failed: Lowest block threshold MUST have 0 as lower bound."
        )
      }
    }

    "created with protocol versions that don't increase monotonically" should {
      "throw an assertion error" in {
        val thrown = the[java.lang.AssertionError] thrownBy {
          ProtocolVersions(
            List(
              Config(0, ProtocolVersion(1), deployConfig),
              Config(11, ProtocolVersion(3), deployConfig)
            )
          )
        }
        compareErrorMessages(thrown, "Protocol major versions should increase monotonically by 1.")
      }
    }

    "created with invalid subsequent semver versions" should {
      "throw an assertion error" in {
        val invalids = Seq(
          "1.0.0" -> "0.1.0",
          "1.0.0" -> "1.0.0",
          "1.0.0" -> "1.2.0",
          "1.0.0" -> "3.0.0",
          "1.0.0" -> "2.1.0",
          "1.2.3" -> "2.0.1"
        )
        forAll(invalids) {
          case (prev, next) =>
            a[java.lang.AssertionError] should be thrownBy {
              ProtocolVersions(
                List(
                  Config(0, semver(prev), deployConfig),
                  Config(11, semver(next), deployConfig)
                )
              )
            }
        }
      }
    }

    "created with valid subsequent semver versions" should {
      "not throw" in {
        val valids = Seq(
          "0.1.0" -> "0.1.3",
          "0.1.0" -> "0.2.0",
          "0.1.0" -> "0.2.3",
          "1.0.0" -> "2.0.0",
          "1.2.3" -> "2.0.0"
        )
        forAll(valids) {
          case (prev, next) =>
            noException should be thrownBy {
              ProtocolVersions(
                List(
                  Config(0, semver(prev), deployConfig),
                  Config(11, semver(next), deployConfig)
                )
              )
            }
        }
      }
    }

    "created with block thresholds that repeat" should {
      "throw an assertion error" in {
        val thrown = the[java.lang.AssertionError] thrownBy {
          ProtocolVersions(
            List(
              Config(0, ProtocolVersion(1), deployConfig),
              Config(10, ProtocolVersion(2), deployConfig),
              Config(10, ProtocolVersion(3), deployConfig)
            )
          )
        }
        compareErrorMessages(thrown, "Block thresholds' lower boundaries can't repeat.")
      }
    }

    "created with correct set of thresholds" should {
      "create instance of ProtocolVersions" in {
        val map = ProtocolVersions(
          List(
            Config(0, ProtocolVersion(1), deployConfig),
            Config(11, ProtocolVersion(2), deployConfig),
            Config(21, ProtocolVersion(3), deployConfig)
          )
        )
        assert(map.versionAt(5) == ProtocolVersion(1))
        assert(map.versionAt(10) == ProtocolVersion(1))
        assert(map.versionAt(11) == ProtocolVersion(2))
        assert(map.versionAt(31) == ProtocolVersion(3))
      }
    }
  }
}
