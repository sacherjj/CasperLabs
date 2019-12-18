package io.casperlabs.models

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey}
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.shared.Sorting.byteStringOrdering

object DeployImplicits {

  private def hash[T <: scalapb.GeneratedMessage](data: T): ByteString =
    ByteString.copyFrom(Blake2b256.hash(data.toByteArray))

  implicit class DeployOps(d: consensus.Deploy) {
    def withHashes: Deploy = {
      val h = d.getHeader.withBodyHash(hash(d.getBody))
      d.withHeader(h).withDeployHash(hash(h))
    }

    def sign(privateKey: PrivateKey, publicKey: PublicKey): Deploy = {
      val sig = Ed25519.sign(d.deployHash.toByteArray, privateKey)
      val approval = consensus
        .Approval()
        .withApproverPublicKey(ByteString.copyFrom(publicKey))
        .withSignature(
          consensus.Signature().withSigAlgorithm(Ed25519.name).withSig(ByteString.copyFrom(sig))
        )
      val approvals = d.approvals.toList :+ approval
      d.withApprovals(approvals.distinct.sortBy(_.approverPublicKey))
    }
  }
}
