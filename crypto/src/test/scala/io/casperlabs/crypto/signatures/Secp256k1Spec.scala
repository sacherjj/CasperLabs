package io.casperlabs.crypto.signatures

import io.casperlabs.crypto.signatures.SignatureAlgorithm.Secp256k1
import org.scalatest.FlatSpec

class Secp256k1Spec extends FlatSpec {

  "Secp256k1" should "generate valid key pair" in {
    assert((1 to 1000).forall { _ =>
      //TODO: change to more meaningful test case or remove
      Secp256k1.newKeyPair
      true
    })
  }
}
