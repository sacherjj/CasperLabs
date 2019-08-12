package io.casperlabs.casper.util.comm

import cats.data.EitherT
import cats.effect.Concurrent
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.CasperConf
import io.casperlabs.casper.genesis.Genesis
import io.casperlabs.casper.genesis.contracts._
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.execengine.{ExecEngineUtil, ProcessedDeployResult}
import io.casperlabs.casper.util.{CasperLabsProtocolVersions, ProcessedDeployUtil, ProtoUtil}
import io.casperlabs.casper.{consensus, LegacyConversions, ValidatorIdentity}
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.protocol.routing.Packet
import io.casperlabs.comm.rp.Connect.RPConfAsk
import io.casperlabs.comm.transport
import io.casperlabs.comm.transport.{Blob, TransportLayer}
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.models.InternalProcessedDeploy
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService
import java.nio.file.Path
import scala.util.Try
import scala.math.BigInt

/**
  * Validator side of the protocol defined in
  * https://rchain.atlassian.net/wiki/spaces/CORE/pages/485556483/Initializing+the+Blockchain+--+Protocol+for+generating+the+Genesis+block
  */
class BlockApproverProtocol(
    validatorId: ValidatorIdentity,
    bonds: Map[PublicKey, Long],
    wallets: Seq[PreWallet],
    conf: BlockApproverProtocol.GenesisConf
) {
  private implicit val logSource: LogSource = LogSource(this.getClass)
  private val _bonds                        = bonds.map(e => ByteString.copyFrom(e._1) -> e._2)

  def unapprovedBlockPacketHandler[F[_]: MonadThrowable: TransportLayer: Log: Time: ErrorHandler: RPConfAsk: ExecutionEngineService: FilesAPI](
      peer: Node,
      u: UnapprovedBlock
  ): F[Unit] =
    if (u.candidate.isEmpty) {
      Log[F]
        .warn("Candidate is not defined.")
        .map(_ => none[Packet])
    } else {
      val candidate = u.candidate.get
      BlockApproverProtocol
        .validateCandidate[F](
          candidate,
          wallets,
          _bonds,
          conf
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
  case class GenesisConf(
      minimumBond: Long,
      maximumBond: Long,
      requiredSigs: Int,
      genesisAccountPublicKeyPath: Option[Path],
      initialTokens: BigInt,
      bondsPath: Option[Path],
      mintCodePath: Option[Path],
      posCodePath: Option[Path]
  )
  object GenesisConf {
    def fromCasperConf(conf: CasperConf) =
      GenesisConf(
        conf.minimumBond,
        conf.maximumBond,
        conf.requiredSigs,
        conf.genesisAccountPublicKeyPath,
        conf.initialMotes,
        Some(conf.bondsFile),
        conf.mintCodePath,
        conf.posCodePath
      )
  }

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

  def validateCandidate[F[_]: MonadThrowable: Log: ExecutionEngineService: FilesAPI](
      candidate: ApprovedBlockCandidate,
      wallets: Seq[PreWallet],
      bonds: Map[ByteString, Long],
      conf: GenesisConf
  ): F[Either[String, Unit]] = {

    def validate: EitherT[F, String, (Seq[InternalProcessedDeploy], RChainState)] =
      for {
        _ <- EitherT.fromEither[F](
              (candidate.requiredSigs == conf.requiredSigs)
                .either(())
                .or("Candidate didn't have required signatures number.")
            )
        block      <- EitherT.fromOption[F](candidate.block, "Candidate block is empty.")
        body       <- EitherT.fromOption[F](block.body, "Body is empty")
        postState  <- EitherT.fromOption[F](body.state, "Post state is empty")
        blockBonds = postState.bonds.map { case Bond(validator, stake) => validator -> stake }.toMap
        _ <- EitherT.fromEither[F](
              (blockBonds == bonds)
                .either(())
                .or("Block bonds don't match expected.")
            )
        validators = blockBonds.toSeq.map(b => ProofOfStakeValidator(b._1.toByteArray, b._2))
        posParams  = ProofOfStakeParams(conf.minimumBond, conf.maximumBond, validators)
        genesisBlessedContracts <- EitherT.liftF(
                                    Genesis
                                      .defaultBlessedTerms[F](
                                        conf.genesisAccountPublicKeyPath,
                                        conf.initialTokens,
                                        posParams,
                                        wallets,
                                        conf.bondsPath,
                                        conf.mintCodePath,
                                        conf.posCodePath
                                      )
                                      .map(_.map(LegacyConversions.fromDeploy(_)).toSet)
                                  )
        blockDeploys = body.deploys.flatMap(ProcessedDeployUtil.toInternal)
        _ <- EitherT.fromEither[F](
              blockDeploys
                .forall(
                  d => genesisBlessedContracts.exists(dd => deployDataEq.eqv(dd, d.deploy))
                )
                .either(())
                .or("Candidate deploys do not match expected deploys.")
            )
        _ <- EitherT.fromEither[F](
              (blockDeploys.size == genesisBlessedContracts.size)
                .either(())
                .or("Mismatch between number of candidate deploys and expected number of deploys.")
            )
      } yield (blockDeploys, postState)

    (for {
      result                    <- validate
      (blockDeploys, postState) = result
      deploys                   = blockDeploys.map(pd => LegacyConversions.toDeploy(pd.deploy))
      protocolVersion = CasperLabsProtocolVersions.thresholdsVersionMap.versionAt(
        postState.blockNumber
      )
      genesisResult <- EitherT(
                        ExecutionEngineService[F].runGenesis(
                          deploys
                            .map(ProtoUtil.deployDataToEEDeploy),
                          protocolVersion
                        )
                      ).leftMap(_.getMessage)
      _ <- EitherT(
            (genesisResult.poststateHash == postState.postStateHash)
              .either(())
              .or("GlobalState root hash mismatch.")
              .pure[F]
          )
    } yield ()).value
  }

  def packetToUnapprovedBlock(msg: Packet): Option[UnapprovedBlock] =
    if (msg.typeId == transport.UnapprovedBlock.id)
      Try(UnapprovedBlock.parseFrom(msg.content.toByteArray)).toOption
    else None

  private val deployDataEq: cats.kernel.Eq[DeployData] = new cats.kernel.Eq[DeployData] {
    override def eqv(x: DeployData, y: DeployData): Boolean =
      x.user == y.user &&
        x.timestamp === y.timestamp &&
        x.signature == y.signature &&
        x.sigAlgorithm === y.sigAlgorithm &&
        x.address == y.address &&
        x.gasPrice === y.gasPrice &&
        x.gasLimit === y.gasLimit &&
        x.nonce === y.nonce &&
        x.getSession.code == y.getSession.code &&
        x.getSession.args == y.getSession.args &&
        x.getPayment.code == y.getPayment.code &&
        x.getPayment.args == y.getPayment.args
  }
}
