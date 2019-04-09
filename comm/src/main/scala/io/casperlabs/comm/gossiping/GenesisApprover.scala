package io.casperlabs.comm.gossiping

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Approval, GenesisCandidate}
import io.casperlabs.comm.ServiceError

/** Accumulate approvals for the Genesis block. When enough of them is
  * present to pass a threshold which is the preorgative of this node,
  * let the rest of the system transition to processing blocks.
  * Keep accumulating and gossiping approvals to facilitate other joiners. */
trait GenesisApprover[F[_]] {

  /** Try to get the candidate, if we already have it. */
  def getCandidate: F[Either[ServiceError, GenesisCandidate]]

  /** Try to add the approval, if we already have the candidate and it matches. If successful, relay it as well. */
  def addApproval(
      blockHash: ByteString,
      approval: Approval
  ): F[Either[ServiceError, Unit]]

  /** Trigger once when the Genesis candidate has gathered enough signatures that this node
	  * can transition to processing blocks and deploys. */
  def onApproved: F[ByteString]
}
