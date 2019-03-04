package io.casperlabs.casper.util.comm

import cats.data.EitherT
import cats.effect.Concurrent
import cats.implicits._
import cats.{Applicative, Monad}
import com.google.protobuf.ByteString
import io.casperlabs.casper.ValidatorIdentity
import io.casperlabs.casper.genesis.Genesis
import io.casperlabs.casper.genesis.contracts._
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.execengine.ExecEngineUtil
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
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.models.InternalProcessedDeploy
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService
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
    bonds: Map[Array[Byte], Long],
    wallets: Seq[PreWallet],
    minimumBond: Long,
    maximumBond: Long,
    faucet: Boolean,
    requiredSigs: Int
) {
  private implicit val logSource: LogSource = LogSource(this.getClass)
  private val _bonds                        = bonds.map(e => ByteString.copyFrom(e._1) -> e._2)

  def unapprovedBlockPacketHandler[F[_]: Concurrent: TransportLayer: Log: Time: ErrorHandler: RPConfAsk: ExecutionEngineService](
      peer: PeerNode,
      u: UnapprovedBlock,
      runtimeManager: RuntimeManager[F]
  ): F[Unit] =
    if (u.candidate.isEmpty) {
      Log[F]
        .warn("Candidate is not defined.")
        .map(_ => none[Packet])
    } else {
      val candidate = u.candidate.get
      BlockApproverProtocol
        .validateCandidate(
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
        .flatMap {
          case Right(_) =>
            for {
              local <- RPConfAsk[F].reader(_.local)
              serializedApproval = BlockApproverProtocol
                .getApproval(candidate, validatorId)
                .toByteString
              msg = Blob(local, Packet(transport.BlockApproval.id, serializedApproval))
              _   <- TransportLayer[F].stream(Seq(peer), msg)
              _ <- Log[F].info(
                    s"Received expected candidate from $peer. Approval sent in response."
                  )
            } yield ()
          case Left(errMsg) =>
            Log[F]
              .warn(s"Received unexpected candidate from $peer because: $errMsg")
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

  def validateCandidate[F[_]: Concurrent: Log: ExecutionEngineService](
      runtimeManager: RuntimeManager[F],
      candidate: ApprovedBlockCandidate,
      requiredSigs: Int,
      timestamp: Long,
      wallets: Seq[PreWallet],
      bonds: Map[ByteString, Long],
      minimumBond: Long,
      maximumBond: Long,
      faucet: Boolean
  ): F[Either[String, Unit]] = {

    def validate: Either[String, (Seq[InternalProcessedDeploy], RChainState)] =
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
        faucetCode = if (faucet) Faucet.basicWalletFaucet(_) else Faucet.noopFaucet
        genesisBlessedContracts = Genesis
          .defaultBlessedTerms(timestamp, posParams, wallets, faucetCode)
          .toSet
        blockDeploys = body.deploys.flatMap(ProcessedDeployUtil.toInternal)
        _ <- blockDeploys
              .forall(
                d => genesisBlessedContracts.exists(dd => deployDataEq.eqv(dd, d.deploy))
              )
              .either(())
              .or("Candidate deploys do not match expected deploys.")
        _ <- (blockDeploys.size == genesisBlessedContracts.size)
              .either(())
              .or("Mismatch between number of candidate deploys and expected number of deploys.")
      } yield (blockDeploys, postState)

    (for {
      result                    <- EitherT(validate.pure[F])
      (blockDeploys, postState) = result
      checkpointsResult <- EitherT(
                            ExecEngineUtil
                              .computeDeploysCheckpoint(
                                Seq(),
                                blockDeploys.map(_.deploy),
                                null,
                                blockMetada => Seq.empty[TransformEntry].pure[F]
                              )
                              .map(_.asRight[String])
                          )
      (_, postStateHash, _, _) = checkpointsResult
      _ <- EitherT(
            (postStateHash == postState.postStateHash)
              .either(())
              .or("Tuplespace hash mismatch.")
              .pure[F]
          )
      tuplespaceBonds <- EitherT(
                          Concurrent[F]
                            .attempt(runtimeManager.computeBonds(postState.postStateHash))
                        ).leftMap(_.getMessage)
      tuplespaceBondsMap = tuplespaceBonds.map {
        case Bond(validator, stake) => validator -> stake
      }.toMap
      _ <- EitherT(
            (tuplespaceBondsMap == bonds)
              .either(())
              .or("Tuplespace bonds don't match expected ones.")
              .pure[F]
          )
    } yield ()).value
  }

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
