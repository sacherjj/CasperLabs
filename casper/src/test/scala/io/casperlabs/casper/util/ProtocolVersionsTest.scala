package io.casperlabs.casper.util

import io.casperlabs.casper.util.ProtocolVersions.BlockThreshold
import io.casperlabs.ipc.ProtocolVersion
import org.scalatest.{Assertion, Matchers, WordSpec}

class ProtocolVersionsTest extends WordSpec with Matchers {

  def compareErrorMessages(error: AssertionError, expected: String): Assertion =
    error.getMessage should equal("assertion failed: " + expected)

  "ProtocolVersion" when {
    "created with lower bound different than 0" should {
      "throw an assertion error" in {
        val thrown = the[java.lang.AssertionError] thrownBy {
          ProtocolVersions(List(BlockThreshold(1, ProtocolVersion(1))))
        }
        compareErrorMessages(thrown, "Lowest block threshold MUST have 0 as lower bound.")
      }
    }

    "created with protocol versions that don't increase monotonically" should {
      "throw an assertion error" in {
        val thrown = the[java.lang.AssertionError] thrownBy {
          ProtocolVersions(
            List(
              BlockThreshold(0, ProtocolVersion(1)),
              BlockThreshold(11, ProtocolVersion(3))
            )
          )
        }
        compareErrorMessages(thrown, "Protocol versions should increase monotonically by 1.")
      }
    }

    "created with block thresholds that repeat" should {
      "throw an assertion error" in {
        val thrown = the[java.lang.AssertionError] thrownBy {
          ProtocolVersions(
            List(
              BlockThreshold(0, ProtocolVersion(1)),
              BlockThreshold(10, ProtocolVersion(2)),
              BlockThreshold(10, ProtocolVersion(3))
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
            BlockThreshold(0, ProtocolVersion(1)),
            BlockThreshold(11, ProtocolVersion(2)),
            BlockThreshold(21, ProtocolVersion(3))
          )
        )
        assert(map.versionAt(5).get == ProtocolVersion(1))
        assert(map.versionAt(10).get == ProtocolVersion(1))
        assert(map.versionAt(11).get == ProtocolVersion(2))
        assert(map.versionAt(31).get == ProtocolVersion(3))
      }
    }
  }
}
