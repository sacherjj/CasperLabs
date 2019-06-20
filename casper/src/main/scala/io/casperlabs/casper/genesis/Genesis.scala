package io.casperlabs.casper.genesis

import java.io.File
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.util.Base64

import cats.effect.{Concurrent, Sync}
import cats.implicits._
import cats.{Applicative, Monad, MonadError}
import com.google.protobuf.ByteString
import io.casperlabs.casper.CasperConf
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.genesis.contracts._
import io.casperlabs.casper.util.ProtoUtil.{blockHeader, deployDataToEEDeploy, unsignedBlockProto}
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.util.{CasperLabsProtocolVersions, ProtoUtil, Sorting}
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS}
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.ipc
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.shared.{Log, LogSource, Resources, Time}
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.BlockMsgWithTransform

import scala.io.Source
import scala.util.{Failure, Success, Try}
import scala.util.control.NoStackTrace
import io.casperlabs.crypto.Keys

object Genesis {

  private implicit val logSource: LogSource = LogSource(this.getClass)

  val protocolVersion = 1L

  private def readFile[F[_]: Sync](path: Path) =
    Sync[F].delay(Files.readAllBytes(path))

  /** Construct deploys that will set up the system contracts. */
  def defaultBlessedTerms[F[_]: Sync: Log](
      timestamp: Long,
      accountPublicKeyPath: Option[Path],
      initialTokens: BigInt,
      posParams: ProofOfStakeParams,
      wallets: Seq[PreWallet],
      mintCodePath: Option[Path],
      posCodePath: Option[Path]
  ): F[List[Deploy]] = {
    def readCode(maybePath: Option[Path]) =
      maybePath.fold(none[ipc.DeployCode].pure[F]) { path =>
        readFile(path) map { bytes =>
          ipc.DeployCode(ByteString.copyFrom(bytes)).some
        }
      }
    // NOTE: In the future the EE might switch to a different model, buf right now it's asking
    // for details that have no representation in a block, and it also doesn't actually execute
    // the deploys we hand over, so for now we're going to pass on the request it needs in a
    // serialized format to capture everything it wants the way it wants it.
    for {
      accountPublicKey <- getAccountPublicKey[F](accountPublicKeyPath)
      mintCode         <- readCode(mintCodePath)
      posCode          <- readCode(posCodePath)
      request = ipc.GenesisRequest(
        address = ByteString.copyFrom(accountPublicKey),
        initialTokens =
          state.BigInt(initialTokens.toString, bitWidth = initialTokens.bitLength).some,
        timestamp = timestamp,
        mintCode = mintCode,
        proofOfStakeCode = posCode,
        protocolVersion = state.ProtocolVersion(protocolVersion).some
      )
      deploy = ProtoUtil.basicDeploy(
        timestamp,
        sessionCode = ByteString.copyFrom(request.toByteArray),
        accountPublicKey = ByteString.copyFrom(accountPublicKey),
        nonce = 1
      )
    } yield List(deploy)
  }

  /** Run system contracts and add them to the block as processed deploys. */
  def withContracts[F[_]: Log: ExecutionEngineService: MonadError[?[_], Throwable]](
      initial: Block,
      blessedTerms: List[Deploy]
  ): F[BlockMsgWithTransform] =
    for {
      _ <- Log[F].debug(s"Processing ${blessedTerms.size} blessed contracts...")
      genesisResult <- MonadError[F, Throwable].rethrow(
                        ExecutionEngineService[F]
                          .runGenesis(
                            blessedTerms.map(deployDataToEEDeploy),
                            CasperLabsProtocolVersions.thresholdsVersionMap.fromBlock(
                              initial
                            )
                          )
                      )
      // TODO: We shouldn't need to do any commutivity checking for the genesis block.
      // Either we make it a "SEQ" block (which is not a feature that exists yet)
      // or there should be a single deploy containing all the blessed contracts.
      transforms    = genesisResult.getEffect.transformMap
      postStateHash = genesisResult.poststateHash
      deploysForBlock = blessedTerms.map { d =>
        Block.ProcessedDeploy().withDeploy(d)
      }

      stateWithContracts = initial.getHeader.getState
        .withPreStateHash(ExecutionEngineService[F].emptyStateHash)
        .withPostStateHash(postStateHash)
      body = Block.Body().withDeploys(deploysForBlock)
      header = blockHeader(
        body,
        parentHashes = Nil,
        justifications = Nil,
        state = stateWithContracts,
        rank = initial.getHeader.rank,
        protocolVersion = initial.getHeader.protocolVersion,
        timestamp = initial.getHeader.timestamp,
        chainId = initial.getHeader.chainId
      )
      unsignedBlock = unsignedBlockProto(body, header)
    } yield BlockMsgWithTransform(Some(unsignedBlock), transforms)

  /** Fill out the basic fields in the block. */
  def withoutContracts(
      bonds: Map[PublicKey, Long],
      timestamp: Long,
      chainId: String
  ): Block = {
    import Sorting.byteArrayOrdering
    import io.casperlabs.crypto.Keys.convertTypeclasses
    //sort to have deterministic order (to get reproducible hash)
    val bondsSorted = bonds.toIndexedSeq.sorted.map {
      case (pk, stake) =>
        val validator = ByteString.copyFrom(pk)
        Bond(validator, stake)
    }

    val state = Block
      .GlobalState()
      .withBonds(bondsSorted)

    val body = Block.Body()

    val header = blockHeader(
      body,
      parentHashes = Nil,
      justifications = Nil,
      state = state,
      rank = 0,
      protocolVersion = protocolVersion,
      timestamp = timestamp,
      chainId = chainId
    )

    unsignedBlockProto(body, header)
  }

  def apply[F[_]: Concurrent: Log: Time: ExecutionEngineService](
      conf: CasperConf
  ): F[BlockMsgWithTransform] = apply[F](
    conf.walletsFile,
    conf.minimumBond,
    conf.maximumBond,
    conf.hasFaucet,
    conf.chainId,
    conf.deployTimestamp,
    conf.genesisAccountPublicKeyPath,
    conf.initialTokens,
    conf.mintCodePath,
    conf.posCodePath
  )

  def apply[F[_]: Concurrent: Log: Time: ExecutionEngineService](
      walletsPath: Path,
      minimumBond: Long,
      maximumBond: Long,
      faucet: Boolean,
      chainId: String,
      deployTimestamp: Option[Long],
      accountPublicKeyPath: Option[Path],
      initialTokens: BigInt,
      mintCodePath: Option[Path],
      posCodePath: Option[Path]
  ): F[BlockMsgWithTransform] =
    for {
      wallets   <- getWallets[F](walletsPath)
      bonds     <- ExecutionEngineService[F].computeBonds(ExecutionEngineService[F].emptyStateHash)
      bondsMap  = bonds.map(b => PublicKey(b.validatorPublicKey.toByteArray) -> b.stake).toMap
      timestamp <- deployTimestamp.fold(Time[F].currentMillis)(_.pure[F])
      initial = withoutContracts(
        bonds = bondsMap,
        timestamp = timestamp,
        chainId = chainId
      )
      validators = bondsMap.map(bond => ProofOfStakeValidator(bond._1, bond._2)).toSeq
      blessedContracts <- defaultBlessedTerms(
                           timestamp = timestamp,
                           accountPublicKeyPath = accountPublicKeyPath,
                           initialTokens = initialTokens,
                           posParams = ProofOfStakeParams(minimumBond, maximumBond, validators),
                           wallets = wallets,
                           mintCodePath = mintCodePath,
                           posCodePath = posCodePath
                         )
      withContr <- withContracts(
                    initial,
                    blessedContracts
                  )
    } yield withContr

  private def toFile[F[_]: Applicative: Log](
      path: Path
  ): F[Option[File]] = {
    val f = path.toFile
    if (f.exists()) f.some.pure[F]
    else none[File].pure[F]
  }

  private def getAccountPublicKey[F[_]: Sync: Log](
      maybePath: Option[Path]
  ): F[Keys.PublicKey] =
    maybePath match {
      case None =>
        Log[F].warn("Using empty account key for genesis.") *>
          Keys.PublicKey(Array.empty[Byte]).pure[F]
      case Some(path) =>
        readFile[F](path)
          .map(new String(_, StandardCharsets.UTF_8))
          .map(Ed25519.tryParsePublicKey(_)) flatMap {
          case Some(key) => key.pure[F]
          case None =>
            MonadThrowable[F].raiseError(
              new IllegalArgumentException(s"Could not parse genesis account key file $path")
            )
        }
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
                        .map(_ => List.empty[PreWallet])
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

  def getBondedValidators[F[_]: Monad: Sync: Log](bondsFile: Option[String]): F[Set[PublicKeyBS]] =
    bondsFile match {
      case None => Set.empty[PublicKeyBS].pure[F]
      case Some(file) =>
        Sync[F]
          .delay {
            Try {
              Source
                .fromFile(file)
                .getLines()
                .map(line => {
                  val Array(pk, _) = line.trim.split(" ")
                  PublicKey(ByteString.copyFrom(Base64.getDecoder.decode(pk)))
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
      bonds: Path,
      numValidators: Int
  ): F[Map[PublicKey, Long]] =
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
                            PublicKey(Base64.getDecoder.decode(pk)) -> (stake.toLong)
                          })
                          .toMap
                      }
                    }
                    .flatMap {
                      case Success(bonds) =>
                        bonds.pure[F]
                      case Failure(_) =>
                        val message = s"Bonds file ${file.getPath} cannot be parsed."
                        Log[F].error(message) *> Sync[F].raiseError[Map[PublicKey, Long]](
                          new IllegalArgumentException(message) with NoStackTrace
                        )
                    }
                case None =>
                  val message = s"Specified bonds file $bonds does not exist."
                  Log[F].error(message) *> Sync[F]
                    .raiseError[Map[PublicKey, Long]](
                      new IllegalArgumentException(message) with NoStackTrace
                    )
              }
    } yield bonds
}
