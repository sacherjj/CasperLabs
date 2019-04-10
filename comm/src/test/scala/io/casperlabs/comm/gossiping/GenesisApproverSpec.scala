package io.casperlabs.comm.gossiping

import org.scalatest._

class GenesisApprovalSpec extends WordSpecLike with ArbitraryConsensus {

  "fromGenesis" when {
    "started with a candidate that passes the threshold" should {
      "immediately trigger the transition" in (pending)
    }
    "started with a candidate that doesn't pass the threshold" should {
      "not trigger the transition" in (pending)
    }
  }
  "fromGenesis" should {
    "create the candidate with the single approval" in (pending)
  }

  "fromBootstrap" should {
    "start polling the bootstrap for a candidate" in (pending)
  }

  "pollBootstrap" should {
    "download the candidate from the bootstrap" in (pending)
    "keep polling until the transition can be made" in (pending)
    "not stop polling if there's an error" in (pending)
    "not accept invalid approvals" in (pending)
    "not accept changes in the candidate" in (pending)
  }

  "addApproval" should {
    "accumulate correct approvals" in (pending)
    "reject incorrect approvals" in (pending)
    "return UNAVAILABLE while there is no candidate" in (pending)
    "return true once the threshold has been passed" in (pending)
    "gossip new approvals" in (pending)
    "not gossip old approvals" in (pending)
    "not stop gossiping if there's an error" in (pending)
    "trigger onApproved when the threshold is passed" in (pending)
  }

  "getCandidate" should {
    "return UNAVAILABLE while there is no candidate" in (pending)
    "return the candidate once its established" in (pending)
  }
}
