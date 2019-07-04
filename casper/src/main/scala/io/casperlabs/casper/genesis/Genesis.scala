package io.casperlabs.casper.genesis

import java.io.File
import java.nio.file.Path
import java.util.Base64

import cats.data.EitherT
import cats.effect.Sync
import cats.implicits._
import cats.{Applicative, Monad, MonadError}
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.CasperConf
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.genesis.contracts._
import io.casperlabs.casper.util.ProtoUtil.{blockHeader, deployDataToEEDeploy, unsignedBlockProto}
import io.casperlabs.casper.util.Sorting._
import io.casperlabs.casper.util.{CasperLabsProtocolVersions, ProtoUtil, Sorting}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS}
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.ipc
import io.casperlabs.shared.{FilesAPI, Log, LogSource}
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.BlockMsgWithTransform

import scala.io.Source
import scala.util.control.NoStackTrace
import scala.util._

object Genesis {

  private implicit val logSource: LogSource = LogSource(this.getClass)

  val protocolVersion = 1L

  /** Construct deploys that will set up the system contracts. */
  @silent("is never used")
  def defaultBlessedTerms[F[_]: MonadThrowable: FilesAPI: Log](
      accountPublicKeyPath: Option[Path],
      initialTokens: BigInt,
      posParams: ProofOfStakeParams,
      wallets: Seq[PreWallet],
      bondsFile: Option[Path],
      mintCodePath: Option[Path],
      posCodePath: Option[Path]
  ): F[List[Deploy]] = {
    def readCode(maybePath: Option[Path]) =
      maybePath.fold(none[ipc.DeployCode].pure[F]) { path =>
        Log[F].info(s"Reading Wasm code from $path") *>
          FilesAPI[F].readBytes(path) map { bytes =>
          ipc.DeployCode(ByteString.copyFrom(bytes)).some
        }
      }
    // NOTE: In the future the EE might switch to a different model, buf right now it's asking
    // for details that have no representation in a block, and it also doesn't actually execute
    // the deploys we hand over, so for now we're going to pass on the request it needs in a
    // serialized format to capture everything it wants the way it wants it.
    def read[A](a: F[Option[A]], ifNone: String) =
      EitherT.fromOptionF[F, String, A](a, ifNone)

    val maybeDeploy = for {
      bondsMap <- read(
                   bondsFile.fold(none[Map[PublicKey, Long]].pure[F])(getBonds[F](_).map(Some(_))),
                   "Bonds file is missing."
                 )
      genesisValidators = bondsMap
        .map {
          case (publicKey, stake) =>
            ipc
              .Bond()
              .withValidatorPublicKey(ByteString.copyFrom(publicKey))
              .withStake(state.BigInt(stake.toString, 512))
        }
        .toSeq
        .sortBy(_.validatorPublicKey)

      accountPublicKey <- read(
                           readAccountPublicKey[F](accountPublicKeyPath),
                           "Genesis account key is missing."
                         )
      mintCode <- read(readCode(mintCodePath), "Mint code is missing.")
      posCode  <- read(readCode(posCodePath), "PoS code is missing.")

      request = ipc
        .GenesisRequest()
        .withAddress(ByteString.copyFrom(accountPublicKey))
        .withInitialTokens(
          state
            .BigInt(
              initialTokens.toString,
              bitWidth = 512 // We could use `initialTokens.bitLength` to map to the closest of 128, 256 or 512 but this is what the EE expects.
            )
        )
        .withMintCode(mintCode)
        .withProofOfStakeCode(posCode)
        .withProtocolVersion(state.ProtocolVersion(protocolVersion))
        .withGenesisValidators(genesisValidators)

      deploy = ProtoUtil.basicDeploy(
        timestamp = 0L,
        sessionCode = ByteString.copyFrom(request.toByteArray),
        accountPublicKey = ByteString.copyFrom(accountPublicKey),
        nonce = 1
      )
    } yield deploy

    maybeDeploy.value flatMap {
      case Left(error) =>
        Log[F].warn(s"Unable to construct blessed terms: $error") *> List.empty[Deploy].pure[F]
      case Right(deploy) =>
        List(deploy).pure[F]
    }
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

  def apply[F[_]: MonadThrowable: Log: FilesAPI: ExecutionEngineService](
      conf: CasperConf
  ): F[BlockMsgWithTransform] = apply[F](
    conf.walletsFile,
    conf.bondsFile,
    conf.minimumBond,
    conf.maximumBond,
    conf.chainId,
    conf.deployTimestamp,
    conf.genesisAccountPublicKeyPath,
    conf.initialTokens,
    conf.mintCodePath,
    conf.posCodePath
  )

  def apply[F[_]: MonadThrowable: Log: FilesAPI: ExecutionEngineService](
      walletsPath: Path,
      bondsPath: Path,
      minimumBond: Long,
      maximumBond: Long,
      chainId: String,
      deployTimestamp: Option[Long],
      accountPublicKeyPath: Option[Path],
      initialTokens: BigInt,
      mintCodePath: Option[Path],
      posCodePath: Option[Path]
  ): F[BlockMsgWithTransform] =
    for {
      wallets   <- getWallets[F](walletsPath)
      bondsMap  <- getBonds[F](bondsPath)
      timestamp = deployTimestamp.getOrElse(0L)
      initial = withoutContracts(
        bonds = bondsMap,
        timestamp = timestamp,
        chainId = chainId
      )
      validators = bondsMap.map(bond => ProofOfStakeValidator(bond._1, bond._2)).toSeq.sortBy(_.id)
      blessedContracts <- defaultBlessedTerms[F](
                           accountPublicKeyPath = accountPublicKeyPath,
                           initialTokens = initialTokens,
                           posParams = ProofOfStakeParams(minimumBond, maximumBond, validators),
                           wallets = wallets,
                           mintCodePath = mintCodePath,
                           posCodePath = posCodePath,
                           bondsFile = Some(bondsPath)
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

  private def readAccountPublicKey[F[_]: MonadThrowable: FilesAPI: Log](
      maybePath: Option[Path]
  ): F[Option[Keys.PublicKey]] =
    maybePath match {
      case None =>
        Log[F].info("Genesis account public key is missing.") *>
          none.pure[F]
      case Some(path) =>
        FilesAPI[F]
          .readString(path)
          .map(Ed25519.tryParsePublicKey)
          .flatMap {
            case None =>
              MonadThrowable[F].raiseError(
                new IllegalArgumentException(s"Could not parse genesis account key file $path")
              )
            case key => key.pure[F]
          }
    }

  def getWallets[F[_]: MonadThrowable: FilesAPI: Log](
      wallets: Path
  ): F[Seq[PreWallet]] = {
    def walletFromFile(file: File): F[Seq[PreWallet]] =
      for {
        maybeLines <- FilesAPI[F].readString(file.toPath).map(_.split('\n').toList).attempt
        wallets <- maybeLines match {
                    case Right(lines) =>
                      lines
                        .traverse(PreWallet.fromLine(_) match {
                          case Right(wallet) => wallet.some.pure[F]
                          case Left(errMsg) =>
                            Log[F]
                              .warn(s"Error in parsing wallets file: $errMsg")
                              .map(_ => none[PreWallet])
                        })
                        .map(_.flatten)
                    case Left(ex) =>
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

  def getBonds[F[_]: MonadThrowable: FilesAPI: Log](
      bonds: Path
  ): F[Map[PublicKey, Long]] =
    for {
      maybeLines <- FilesAPI[F].readString(bonds).map(_.split('\n').toList).attempt
      bonds <- maybeLines match {
                case Right(lines) =>
                  MonadThrowable[F]
                    .fromTry(
                      lines
                        .traverse(
                          line =>
                            Try {
                              val Array(pk, stake) = line.trim.split(" ")
                              PublicKey(Base64.getDecoder.decode(pk)) -> stake.toLong
                            }
                        )
                    )
                    .map(_.toMap)
                    .handleErrorWith { ex =>
                      val message = s"Bonds file $bonds cannot be parsed."
                      Log[F].error(message, ex) *> MonadThrowable[F]
                        .raiseError[Map[PublicKey, Long]](
                          new IllegalArgumentException(message) with NoStackTrace
                        )
                    }
                case Left(ex) =>
                  val message = s"Specified bonds file $bonds does not exist."
                  Log[F].error(message, ex) *> MonadThrowable[F]
                    .raiseError[Map[PublicKey, Long]](
                      new IllegalArgumentException(message) with NoStackTrace
                    )
              }
    } yield bonds
}
