package io.casperlabs.casper.util

import cats.effect.{Resource, Sync}
import cats.implicits._
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.casper.util.rholang.RuntimeManager
import io.casperlabs.casper.util.ProtoUtil.{deployDataToDeploy, sourceDeploy}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.hash.{Blake2b256, Keccak256}
import io.casperlabs.crypto.signatures.{Ed25519, Secp256k1}
import io.casperlabs.shared.PathOps.RichPath
import java.io.PrintWriter
import java.nio.file.{Files, Path}

import cats.Id
import io.casperlabs.smartcontracts.SmartContractsApi
import monix.eval.Task
import monix.execution.Scheduler

object BondingUtil {
  def bondingForwarderAddress(ethAddress: String): String = s"${ethAddress}_bondingForwarder"
  def bondingStatusOut(ethAddress: String): String        = s"${ethAddress}_bondingOut"
  def transferStatusOut(ethAddress: String): String       = s"${ethAddress}_transferOut"

  def bondingForwarderDeploy(bondKey: String, ethAddress: String): String = """"""

  def unlockDeploy[F[_]: Sync](ethAddress: String, pubKey: String, secKey: String)(
      implicit runtimeManager: RuntimeManager[Task],
      scheduler: Scheduler
  ): F[String] =
    preWalletUnlockDeploy(ethAddress, pubKey, Base16.decode(secKey), s"${ethAddress}_unlockOut")

  def issuanceBondDeploy[F[_]: Sync](
      amount: Long,
      ethAddress: String,
      pubKey: String,
      secKey: String
  )(
      implicit runtimeManager: RuntimeManager[Task],
      scheduler: Scheduler
  ): F[String] =
    issuanceWalletTransferDeploy(
      0, //nonce
      amount,
      bondingForwarderAddress(ethAddress),
      transferStatusOut(ethAddress),
      pubKey,
      Base16.decode(secKey)
    )

  def preWalletUnlockDeploy[F[_]: Sync](
      ethAddress: String,
      pubKey: String,
      secKey: Array[Byte],
      statusOut: String
  )(implicit runtimeManager: RuntimeManager[Task], scheduler: Scheduler): F[String] = ???

  def walletTransferSigData[F[_]: Sync](
      nonce: Int,
      amount: Long,
      destination: String
  )(implicit runtimeManager: RuntimeManager[Task], scheduler: Scheduler): F[Array[Byte]] = ???

  def issuanceWalletTransferDeploy[F[_]: Sync](
      nonce: Int,
      amount: Long,
      destination: String,
      transferStatusOut: String,
      pubKey: String,
      secKey: Array[Byte]
  )(implicit runtimeManager: RuntimeManager[Task], scheduler: Scheduler): F[String] = """""".pure[F]

  def faucetBondDeploy[F[_]: Sync](
      amount: Long,
      sigAlgorithm: String,
      pubKey: String,
      secKey: Array[Byte]
  )(implicit runtimeManager: RuntimeManager[Task], scheduler: Scheduler): F[String] = """""".pure[F]

  def writeFile[F[_]: Sync](name: String, content: String): F[Unit] = {
    val file =
      Resource.make[F, PrintWriter](Sync[F].delay { new PrintWriter(name) })(
        pw => Sync[F].delay { pw.close() }
      )
    file.use(pw => Sync[F].delay { pw.println(content) })
  }

  def makeRuntimeDir[F[_]: Sync]: Resource[F, Path] =
    Resource.make[F, Path](Sync[F].delay { Files.createTempDirectory("casper-bonding-helper-") })(
      runtimeDir => Sync[F].delay { runtimeDir.recursivelyDelete() }
    )

  // is it smaller than the actual size?
  // can you sen my screen?
  def makeSmartContractsApiResource[F[_]: Sync](
      runtimeDirResource: Resource[F, Path]
  )(implicit scheduler: Scheduler): Resource[F, SmartContractsApi[Task]] = ???

  def makeRuntimeManagerResource[F[_]: Sync](
      smartContractsApiResource: Resource[F, SmartContractsApi[Task]]
  )(implicit scheduler: Scheduler): Resource[F, RuntimeManager[Task]] =
    smartContractsApiResource.flatMap(
      smartContractsApi =>
        Resource
          .make(RuntimeManager.fromSmartContractApi(smartContractsApi).pure[F])(_ => Sync[F].unit)
    )

  def bondingDeploy[F[_]: Sync](
      bondKey: String,
      ethAddress: String,
      amount: Long,
      secKey: String,
      pubKey: String
  )(implicit scheduler: Scheduler): F[Unit] = ???

  def writeFaucetBasedRhoFiles[F[_]: Sync](
      amount: Long,
      sigAlgorithm: String,
      secKey: String,
      pubKey: String
  )(implicit scheduler: Scheduler): F[Unit] = ???

}
