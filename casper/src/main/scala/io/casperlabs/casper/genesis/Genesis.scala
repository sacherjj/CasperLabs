package io.casperlabs.casper.genesis

import java.io.{File, PrintWriter}
import java.nio.file.Path

import cats.effect.{Concurrent, Sync}
import cats.implicits._
import cats.{Applicative, Foldable, Monad, MonadError}
import com.google.protobuf.ByteString
import io.casperlabs.casper.genesis.contracts._
import io.casperlabs.casper.protocol
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.ProtoUtil.{blockHeader, deployDataToEEDeploy, unsignedBlockProto}
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.util.{CasperLabsProtocolVersions, Sorting}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.signatures.Ed25519
import io.casperlabs.shared.{Log, LogSource, Time}
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.BlockMsgWithTransform

import scala.io.Source
import scala.util.{Failure, Success, Try}

object Genesis {

  private implicit val logSource: LogSource = LogSource(this.getClass)

  // Todo: there should be some initial contracts like Mint, POS or something else
  def defaultBlessedTerms(
      timestamp: Long,
      posParams: ProofOfStakeParams,
      wallets: Seq[PreWallet],
      faucetCode: String => String
  ): List[DeployData] =
    List()

  def withContracts[F[_]: Log: ExecutionEngineService: MonadError[?[_], Throwable]](
      initial: BlockMessage,
      posParams: ProofOfStakeParams,
      wallets: Seq[PreWallet],
      faucetCode: String => String,
      startHash: StateHash,
      timestamp: Long
  ): F[BlockMsgWithTransform] =
    withContracts(
      defaultBlessedTerms(timestamp, posParams, wallets, faucetCode),
      initial,
      startHash
    )

  def withContracts[F[_]: Log: ExecutionEngineService: MonadError[?[_], Throwable]](
      blessedTerms: List[DeployData],
      initial: BlockMessage,
      startHash: StateHash
  ): F[BlockMsgWithTransform] =
    for {
      _ <- Log[F].debug(s"Processing ${blessedTerms.size} blessed contracts...")
      processedDeploys <- MonadError[F, Throwable].rethrow(
                           ExecutionEngineService[F]
                             .exec(
                               startHash,
                               blessedTerms.map(deployDataToEEDeploy),
                               CasperLabsProtocolVersions.thresholdsVersionMap.fromBlockMessage(
                                 initial
                               )
                             )
                         )
      // TODO: We shouldn't need to do any commutivity checking for the genesis block.
      // Either we make it a "SEQ" block (which is not a feature that exists yet)
      // or there should be a single deploy containing all the blessed contracts.
      deployEffects = ExecEngineUtil.findCommutingEffects(
        ExecEngineUtil.processedDeployEffects(blessedTerms zip processedDeploys)
      )
      _               <- Log[F].debug(s"Selected ${deployEffects.size} non-conflicing blessed contracts.")
      deploysForBlock = ExecEngineUtil.extractProcessedDepoys(deployEffects)
      transforms      = ExecEngineUtil.extractTransforms(deployEffects)
      _ <- Log[F].debug(
            s"Commiting blessed deploy effects onto starting hash ${Base16.encode(startHash.toByteArray)}..."
          )
      postStateHash <- MonadError[F, Throwable].rethrow(
                        ExecutionEngineService[F].commit(startHash, transforms)
                      )
      stateWithContracts = for {
        bd <- initial.body
        ps <- bd.state
      } yield
        ps.withPreStateHash(ExecutionEngineService[F].emptyStateHash)
          .withPostStateHash(postStateHash)
      protocolVersion = initial.header.get.protocolVersion
      timestamp       = initial.header.get.timestamp
      body            = Body(state = stateWithContracts, deploys = deploysForBlock)
      header          = blockHeader(body, List.empty[ByteString], protocolVersion, timestamp)
      unsignedBlock   = unsignedBlockProto(body, header, List.empty[Justification], initial.shardId)
    } yield BlockMsgWithTransform(Some(unsignedBlock), transforms)

  def withoutContracts(
      bonds: Map[Array[Byte], Long],
      version: Long,
      timestamp: Long,
      shardId: String
  ): BlockMessage = {
    import Sorting.byteArrayOrdering
    //sort to have deterministic order (to get reproducible hash)
    val bondsProto = bonds.toIndexedSeq.sorted.map {
      case (pk, stake) =>
        val validator = ByteString.copyFrom(pk)
        Bond(validator, stake)
    }

    val state = RChainState()
      .withBlockNumber(0)
      .withBonds(bondsProto)
    val body = Body()
      .withState(state)
    val header = blockHeader(body, List.empty[ByteString], version, timestamp)

    unsignedBlockProto(body, header, List.empty[Justification], shardId)
  }

  //TODO: Decide on version number and shard identifier
  def apply[F[_]: Concurrent: Log: Time: ExecutionEngineService](
      walletsPath: Path,
      minimumBond: Long,
      maximumBond: Long,
      faucet: Boolean,
      shardId: String,
      deployTimestamp: Option[Long]
  ): F[BlockMsgWithTransform] =
    for {
      wallets   <- getWallets[F](walletsPath)
      bonds     <- ExecutionEngineService[F].computeBonds(ExecutionEngineService[F].emptyStateHash)
      bondsMap  = bonds.map(b => b.validator.toByteArray -> b.stake).toMap
      timestamp <- deployTimestamp.fold(Time[F].currentMillis)(_.pure[F])
      initial = withoutContracts(
        bonds = bondsMap,
        timestamp = 1L,
        version = 1L,
        shardId = shardId
      )
      validators = bondsMap.map(bond => ProofOfStakeValidator(bond._1, bond._2)).toSeq
      faucetCode = if (faucet) Faucet.basicWalletFaucet(_) else Faucet.noopFaucet
      withContr <- withContracts(
                    initial,
                    ProofOfStakeParams(minimumBond, maximumBond, validators),
                    wallets,
                    faucetCode,
                    ExecutionEngineService[F].emptyStateHash,
                    timestamp
                  )
    } yield withContr

  def toFile[F[_]: Applicative: Log](
      path: Path
  ): F[Option[File]] = {
    val f = path.toFile
    if (f.exists()) f.some.pure[F]
    else none[File].pure[F]
  }

  def getWallets[F[_]: Sync: Log](
      wallets: Path
  ): F[Seq[PreWallet]] = {
    def walletFromFile(file: File): F[Seq[PreWallet]] =
      for {
        maybeLines <- Sync[F].delay { Try(Source.fromFile(file).getLines().toList) }
        wallets <- maybeLines match {
                    case Success(lines) =>
                      lines
                        .traverse(PreWallet.fromLine(_) match {
                          case Right(wallet) => wallet.some.pure[F]
                          case Left(errMsg) =>
                            Log[F]
                              .warn(s"Error in parsing wallets file: $errMsg")
                              .map(_ => none[PreWallet])
                        })
                        .map(_.flatten)
                    case Failure(ex) =>
                      Log[F]
                        .warn(
                          s"Failed to read ${file.getAbsolutePath} for reason: ${ex.getMessage}"
                        )
                        .map(_ => Seq.empty[PreWallet])
                  }
      } yield wallets

    for {
      walletsFile <- toFile[F](wallets)
      wallets <- walletsFile match {
                  case Some(file) => walletFromFile(file)
                  case _ =>
                    Log[F]
                      .warn(
                        s"Specified wallets file $wallets does not exist. No wallets will exist at genesis."
                      )
                      .map(_ => Seq.empty[PreWallet])
                }
    } yield wallets
  }

  def getBondedValidators[F[_]: Monad: Sync: Log](bondsFile: Option[String]): F[Set[ByteString]] =
    bondsFile match {
      case None => Set.empty[ByteString].pure[F]
      case Some(file) =>
        Sync[F]
          .delay {
            Try {
              Source
                .fromFile(file)
                .getLines()
                .map(line => {
                  val Array(pk, _) = line.trim.split(" ")
                  ByteString.copyFrom(Base16.decode(pk))
                })
                .toSet
            }
          }
          .flatMap {
            case Failure(th) =>
              Log[F]
                .warn(s"Failed to parse bonded validators file $file for reason ${th.getMessage}")
                .map(_ => Set.empty)
            case Success(x) => x.pure[F]
          }
    }

  def getBonds[F[_]: Sync: Log](
      genesisPath: Path,
      bonds: Path,
      numValidators: Int
  ): F[Map[Array[Byte], Long]] =
    for {
      bondsFile <- toFile[F](bonds)
      bonds <- bondsFile match {
                case Some(file) =>
                  Sync[F]
                    .delay {
                      Try {
                        Source
                          .fromFile(file)
                          .getLines()
                          .map(line => {
                            val Array(pk, stake) = line.trim.split(" ")
                            Base16.decode(pk) -> (stake.toLong)
                          })
                          .toMap
                      }
                    }
                    .flatMap {
                      case Success(bonds) =>
                        bonds.pure[F]
                      case Failure(_) =>
                        Log[F].warn(
                          s"Bonds file ${file.getPath} cannot be parsed. Falling back on generating random validators."
                        ) *> newValidators[F](numValidators, genesisPath)
                    }
                case None =>
                  Log[F].warn(
                    s"Specified bonds file $bondsFile does not exist. Falling back on generating random validators."
                  ) *>
                    newValidators[F](numValidators, genesisPath)
              }
    } yield bonds

  private def newValidators[F[_]: Sync: Log](
      numValidators: Int,
      genesisPath: Path
  ): F[Map[Array[Byte], Long]] = {
    val keys         = Vector.fill(numValidators)(Ed25519.newKeyPair)
    val (_, pubKeys) = keys.unzip
    val bonds        = pubKeys.zipWithIndex.toMap.mapValues(_.toLong + 1L)
    val genBondsFile = genesisPath.resolve(s"bonds.txt").toFile

    val skFiles = Sync[F].delay {
      genesisPath.toFile.mkdir()
      keys.foreach { //create files showing the secret key for each public key
        case (sec, pub) =>
          val sk      = Base16.encode(sec)
          val pk      = Base16.encode(pub)
          val skFile  = genesisPath.resolve(s"$pk.sk").toFile
          val printer = new PrintWriter(skFile)
          printer.println(sk)
          printer.close()
      }
    }

    //create bonds file for editing/future use
    for {
      _       <- skFiles
      printer <- Sync[F].delay { new PrintWriter(genBondsFile) }
      _ <- Foldable[List].foldM[F, (Array[Byte], Long), Unit](bonds.toList, ()) {
            case (_, (pub, stake)) =>
              val pk = Base16.encode(pub)
              Log[F].info(s"Created validator $pk with bond $stake") *>
                Sync[F].delay { printer.println(s"$pk $stake") }
          }
      _ <- Sync[F].delay { printer.close() }
    } yield bonds
  }

}
