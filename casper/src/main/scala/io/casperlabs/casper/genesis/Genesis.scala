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
import io.casperlabs.blockstorage.BlockStorage
import io.casperlabs.casper.{CasperConf, PrettyPrinter}
import io.casperlabs.casper.consensus._
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

  /** Fill out the basic fields in the block. */
  private def withoutContracts(
      bonds: Map[PublicKey, Long],
      timestamp: Long,
      chainId: String,
      protocolVersion: Long
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

  // https://casperlabs.atlassian.net/wiki/spaces/EN/pages/135528449/Genesis+Process+Specification
  def fromChainSpec[F[_]: MonadThrowable: Log: ExecutionEngineService: BlockStorage](
      genesisConfig: ipc.ChainSpec.GenesisConfig
  ): F[BlockMsgWithTransform] =
    for {
      // Execute the EE genesis setup based on the chain spec.
      genesisResult <- MonadError[F, Throwable].rethrow(
                        ExecutionEngineService[F]
                          .runGenesis(
                            genesisConfig
                          )
                      )
      bondsMap = genesisConfig.accounts.collect {
        case account if account.bondedAmount.isDefined && account.getBondedAmount.value != "0" =>
          PublicKey(account.publicKey.toByteArray) -> account.getBondedAmount.value.toLong
      }.toMap

      initial = withoutContracts(
        bonds = bondsMap,
        timestamp = genesisConfig.timestamp,
        chainId = genesisConfig.name,
        protocolVersion = genesisConfig.getProtocolVersion.value
      )
      transforms    = genesisResult.getEffect.transformMap
      postStateHash = genesisResult.poststateHash

      stateWithContracts = initial.getHeader.getState
        .withPreStateHash(ExecutionEngineService[F].emptyStateHash)
        .withPostStateHash(postStateHash)

      // Chain spec based Genesis will have an empty body,
      // no deploys, so everyone has to calculate it themselves.
      body = Block.Body()

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

      genesis = BlockMsgWithTransform(Some(unsignedBlock), transforms)

      // Since we are not passing the deploys in the body,
      // we must commit the effects here.
      _ <- MonadError[F, Throwable].rethrow(
            ExecutionEngineService[F]
              .commit(
                genesis.getBlockMessage.getHeader.getState.preStateHash,
                transforms
              )
          )

      // And store the block as well since we won't have any other means of retrieving its effects.
      _ <- BlockStorage[F].put(genesis)
    } yield genesis
}
