package io.casperlabs.casper.util

import io.casperlabs.casper.util.ProtocolVersions.BlockThreshold
import io.casperlabs.ipc.ProtocolVersion
import org.scalatest.{Assertion, Matchers, WordSpec}

class ProtocolVersionsTest extends WordSpec with Matchers {

  def compareErrorMessages(error: AssertionError, expected: String): Assertion =
    error.getMessage should equal("assertion failed: " + expected)

  "ProtocolVersion" when {
    "created with upper bound" should {
      "throw an assertion error" in {
        val thrown = the[java.lang.AssertionError] thrownBy {
          ProtocolVersionsMap(Map(BlockThreshold(0, Some(10)) -> ProtocolVersion(1)))
        }
        compareErrorMessages(
          thrown,
          "Highest block threshold MUSTN'T have upper bound."
        )
      }
    }

    "created with lower bound different than 0" should {
      "throw an assertion error" in {
        val thrown = the[java.lang.AssertionError] thrownBy {
          ProtocolVersionsMap(Map(BlockThreshold(1, None) -> ProtocolVersion(1)))
        }
        compareErrorMessages(thrown, "Lowest block threshold MUST have 0 as lower bound.")
      }
    }

    "created with protocol versions that don't increase monotonically" should {
      "throw an assertion error" in {
        val thrown = the[java.lang.AssertionError] thrownBy {
          ProtocolVersionsMap(
            Map(
              BlockThreshold(0, Some(10)) -> ProtocolVersion(1),
              BlockThreshold(11, None)    -> ProtocolVersion(3)
            )
          )
        }
        compareErrorMessages(thrown, "Protocol versions should increase monotonically by 1.")
      }
    }

    "created with block thresholds that overlap" should {
      "throw an assertion error" in {
        val thrown = the[java.lang.AssertionError] thrownBy {
          ProtocolVersionsMap(
            Map(
              BlockThreshold(0, Some(10)) -> ProtocolVersion(1),
              BlockThreshold(10, None)    -> ProtocolVersion(2)
            )
          )
        }
        compareErrorMessages(thrown, "Block thresholds can't overlap.")
      }
    }

    "created with non-contiguous thresholds" should {
      "throw an assertion error" in {
        val thrown = the[java.lang.AssertionError] thrownBy {
          ProtocolVersionsMap(
            Map(
              BlockThreshold(0, Some(10)) -> ProtocolVersion(1),
              BlockThreshold(12, None)    -> ProtocolVersion(2)
            )
          )
        }
        compareErrorMessages(thrown, "Block thresholds have to be contiguous (no gaps).")
      }
    }

    "created with correct set of thresholds" should {
      "create instance of ProtocolVersionsMap" in {
        val map = ProtocolVersionsMap(
          Map(
            BlockThreshold(0, Some(10))  -> ProtocolVersion(1),
            BlockThreshold(11, Some(20)) -> ProtocolVersion(2),
            BlockThreshold(21, None)     -> ProtocolVersion(3)
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
