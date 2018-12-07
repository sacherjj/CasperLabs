package coop.rchain.casper.util

import cats.effect.{Resource, Sync}
import cats.implicits._

import coop.rchain.catscontrib.TaskContrib._
import coop.rchain.casper.util.rholang.RuntimeManager
import coop.rchain.casper.util.ProtoUtil.{deployDataToDeploy, sourceDeploy}
import coop.rchain.crypto.codec.Base16
import coop.rchain.crypto.hash.{Blake2b256, Keccak256}
import coop.rchain.crypto.signatures.{Ed25519, Secp256k1}
import coop.rchain.shared.PathOps.RichPath

import java.io.PrintWriter
import java.nio.file.{Files, Path}

import monix.execution.Scheduler

object BondingUtil {
  def bondingForwarderAddress(ethAddress: String): String = s"${ethAddress}_bondingForwarder"
  def bondingStatusOut(ethAddress: String): String        = s"${ethAddress}_bondingOut"
  def transferStatusOut(ethAddress: String): String       = s"${ethAddress}_transferOut"

  def bondingForwarderDeploy(bondKey: String, ethAddress: String): String = ???

  def unlockDeploy[F[_]: Sync](ethAddress: String, pubKey: String, secKey: String)(
      implicit runtimeManager: RuntimeManager,
      scheduler: Scheduler
  ): F[String] =
    preWalletUnlockDeploy(ethAddress, pubKey, Base16.decode(secKey), s"${ethAddress}_unlockOut")

  def issuanceBondDeploy[F[_]: Sync](
      amount: Long,
      ethAddress: String,
      pubKey: String,
      secKey: String
  )(
      implicit runtimeManager: RuntimeManager,
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
  )(implicit runtimeManager: RuntimeManager, scheduler: Scheduler): F[String] = ???

  def walletTransferSigData[F[_]: Sync](
      nonce: Int,
      amount: Long,
      destination: String
  )(implicit runtimeManager: RuntimeManager, scheduler: Scheduler): F[Array[Byte]] = ???

  def issuanceWalletTransferDeploy[F[_]: Sync](
      nonce: Int,
      amount: Long,
      destination: String,
      transferStatusOut: String,
      pubKey: String,
      secKey: Array[Byte]
  )(implicit runtimeManager: RuntimeManager, scheduler: Scheduler): F[String] = ???

  def faucetBondDeploy[F[_]: Sync](
      amount: Long,
      sigAlgorithm: String,
      pubKey: String,
      secKey: Array[Byte]
  )(implicit runtimeManager: RuntimeManager, scheduler: Scheduler): F[String] = ???

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
  def makeRuntimeResource[F[_]: Sync](
      runtimeDirResource: Resource[F, Path]
  )(implicit scheduler: Scheduler): Resource[F, Runtime] = ???

  def makeRuntimeManagerResource[F[_]: Sync](
      runtimeResource: Resource[F, Runtime]
  )(implicit scheduler: Scheduler): Resource[F, RuntimeManager] =
    runtimeResource.flatMap(
      activeRuntime =>
        Resource.make(RuntimeManager.fromRuntime(activeRuntime).pure[F])(_ => Sync[F].unit)
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
