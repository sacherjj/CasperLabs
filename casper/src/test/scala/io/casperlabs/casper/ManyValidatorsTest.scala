package io.casperlabs.casper

import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper._
import io.casperlabs.casper.util.BondingUtil.Bond
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.immutable.HashMap
import scala.util.Random

@silent("deprecated")
class ManyValidatorsTest extends FlatSpec with Matchers with BlockGenerator with StorageFixture {

  "Show blocks" should "be processed quickly for a node with 300 validators" in withStorage {
    implicit blockStorage => implicit dagStorage => implicit deployStorage => _ =>
      val bonds = Seq
        .fill(300)(
          ByteString.copyFromUtf8(Random.nextString(20)).substring(0, 32)
        )
        .map(Bond(_, 10))
      val v1 = bonds.head.validatorPublicKey
      for {
        genesis <- createAndStoreMessage[Task](Seq(), ByteString.EMPTY, bonds)
        _ <- createAndStoreMessage[Task](Seq(genesis.blockHash), v1, bonds, bonds.map {
              case Bond(validator, _) => validator -> genesis.blockHash
            }.toMap)
        dag                 <- dagStorage.getRepresentation
        latestMessageHashes <- dag.latestMessageHashes
        equivocators        <- dag.getEquivocators
        tips <- Estimator.tips[Task](
                 dag,
                 genesis.blockHash,
                 latestMessageHashes,
                 equivocators
               )
        casperEffect <- NoOpsCasperEffect[Task](
                         HashMap.empty[BlockHash, BlockMsgWithTransform],
                         tips
                       )
        implicit0(casperRef: MultiParentCasperRef[Task]) <- MultiParentCasperRef.of[Task]
        _                                                <- casperRef.set(casperEffect)

        result <- BlockAPI.getBlockInfos[Task](Int.MaxValue)
      } yield result
  }
}
