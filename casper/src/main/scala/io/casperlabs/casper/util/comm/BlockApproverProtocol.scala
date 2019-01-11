package io.casperlabs.casper.util.comm

import cats.implicits._
import cats.{Id, Monad}
import com.google.protobuf.ByteString
import io.casperlabs.casper.ValidatorIdentity
import io.casperlabs.casper.genesis.Genesis
import io.casperlabs.casper.genesis.contracts._
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.rholang.{ProcessedDeployUtil, RuntimeManager}
import io.casperlabs.catscontrib.Capture
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm.protocol.routing.Packet
import io.casperlabs.comm.rp.Connect.RPConfAsk
import io.casperlabs.comm.transport.{Blob, TransportLayer}
import io.casperlabs.comm.{transport, PeerNode}
import io.casperlabs.comm.transport
import io.casperlabs.comm.transport.{Blob, TransportLayer}
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.shared._
import monix.eval.Task
import monix.execution.Scheduler

import scala.concurrent.duration.Duration
import scala.util.Try

/**
  * Validator side of the protocol defined in
  * https://rchain.atlassian.net/wiki/spaces/CORE/pages/485556483/Initializing+the+Blockchain+--+Protocol+for+generating+the+Genesis+block
  */
class BlockApproverProtocol(
    validatorId: ValidatorIdentity,
    deployTimestamp: Long,
    runtimeManager: RuntimeManager[Task],
    bonds: Map[Array[Byte], Long],
    wallets: Seq[PreWallet],
    minimumBond: Long,
    maximumBond: Long,
    faucet: Boolean,
    requiredSigs: Int
)(implicit scheduler: Scheduler) {
  private implicit val logSource: LogSource = LogSource(this.getClass)
  private val _bonds                        = bonds.map(e => ByteString.copyFrom(e._1) -> e._2)

  def unapprovedBlockPacketHandler[F[_]: Capture: Monad: TransportLayer: Log: Time: ErrorHandler: RPConfAsk](
      peer: PeerNode,
      u: UnapprovedBlock
  ): F[Option[Packet]] =
    if (u.candidate.isEmpty) {
      Log[F]
        .warn("Candidate is not defined.")
        .map(_ => none[Packet])
    } else {
      val candidate = u.candidate.get
      val validCandidate = BlockApproverProtocol.validateCandidate(
        runtimeManager,
        candidate,
        requiredSigs,
        deployTimestamp,
        wallets,
        _bonds,
        minimumBond,
        maximumBond,
        faucet
      )
      validCandidate match {
        case Right(_) =>
          for {
            local <- RPConfAsk[F].reader(_.local)
            serializedApproval = BlockApproverProtocol
              .getApproval(candidate, validatorId)
              .toByteString
            msg = Blob(local, Packet(transport.BlockApproval.id, serializedApproval))
            _   <- TransportLayer[F].stream(Seq(peer), msg)
            _   <- Log[F].info(s"Received expected candidate from $peer. Approval sent in response.")
          } yield none[Packet]
        case Left(errMsg) =>
          Log[F]
            .warn(s"Received unexpected candidate from $peer because: $errMsg")
            .map(_ => none[Packet])
      }
    }
}

object BlockApproverProtocol {
  def getBlockApproval(
      expectedCandidate: ApprovedBlockCandidate,
      validatorId: ValidatorIdentity
  ): BlockApproval = {
    val sigData = Blake2b256.hash(expectedCandidate.toByteArray)
    val sig     = validatorId.signature(sigData)
    BlockApproval(Some(expectedCandidate), Some(sig))
  }

  def getApproval(
      candidate: ApprovedBlockCandidate,
      validatorId: ValidatorIdentity
  ): BlockApproval =
    getBlockApproval(candidate, validatorId)

  def validateCandidate(
      runtimeManager: RuntimeManager[Task],
      candidate: ApprovedBlockCandidate,
      requiredSigs: Int,
      timestamp: Long,
      wallets: Seq[PreWallet],
      bonds: Map[ByteString, Long],
      minimumBond: Long,
      maximumBond: Long,
      faucet: Boolean
  )(implicit scheduler: Scheduler): Either[String, Unit] =
    for {
      _ <- (candidate.requiredSigs == requiredSigs)
            .either(())
            .or("Candidate didn't have required signatures number.")
      block      <- Either.fromOption(candidate.block, "Candidate block is empty.")
      body       <- Either.fromOption(block.body, "Body is empty")
      postState  <- Either.fromOption(body.state, "Post state is empty")
      blockBonds = postState.bonds.map { case Bond(validator, stake) => validator -> stake }.toMap
      _ <- (blockBonds == bonds)
            .either(())
            .or("Block bonds don't match expected.")
      validators = blockBonds.toSeq.map(b => ProofOfStakeValidator(b._1.toByteArray, b._2))
      posParams  = ProofOfStakeParams(minimumBond, maximumBond, validators)
      faucetCode = if (faucet) Faucet.basicWalletFaucet _ else Faucet.noopFaucet
      genesisBlessedContracts = Genesis
        .defaultBlessedTerms(timestamp, posParams, wallets, faucetCode)
        .toSet
      blockDeploys          = body.deploys.flatMap(ProcessedDeployUtil.toInternal)
      genesisBlessedDeploys = genesisBlessedContracts.flatMap(_.raw)
      _ <- blockDeploys
            .forall(
              d => genesisBlessedDeploys.exists(dd => deployDataEq.eqv(dd, d.deploy.raw.get))
            )
            .either(())
            .or("Candidate deploys do not match expected deploys.")
      _ <- (blockDeploys.size == genesisBlessedContracts.size)
            .either(())
            .or("Mismatch between number of candidate deploys and expected number of deploys.")
      stateHash <- runtimeManager
                    .replayComputeState(runtimeManager.emptyStateHash, blockDeploys)
                    .runSyncUnsafe(Duration.Inf)
                    .leftMap { case (_, status) => s"Failed status during replay: $status." }
      _ <- (stateHash == postState.postStateHash)
            .either(())
            .or("Tuplespace hash mismatch.")
      tuplespaceBonds <- Try(runtimeManager.computeBonds(postState.postStateHash)).toEither
                          .leftMap(_.getMessage)
      tuplespaceBondsMap = tuplespaceBonds.map { case Bond(validator, stake) => validator -> stake }.toMap
      _ <- (tuplespaceBondsMap == bonds)
            .either(())
            .or("Tuplespace bonds don't match expected ones.")
    } yield ()

  def packetToUnapprovedBlock(msg: Packet): Option[UnapprovedBlock] =
    if (msg.typeId == transport.UnapprovedBlock.id)
      Try(UnapprovedBlock.parseFrom(msg.content.toByteArray)).toOption
    else None

  val deployDataEq: cats.kernel.Eq[DeployData] = new cats.kernel.Eq[DeployData] {
    override def eqv(x: DeployData, y: DeployData): Boolean =
      x.user == y.user &&
        x.timestamp === y.timestamp &&
        x.signature == y.signature &&
        x.sigAlgorithm === y.sigAlgorithm &&
        x.address == y.address &&
        x.gasPrice === y.gasPrice &&
        x.gasLimit === y.gasLimit &&
        x.nonce === y.nonce
  }
}
