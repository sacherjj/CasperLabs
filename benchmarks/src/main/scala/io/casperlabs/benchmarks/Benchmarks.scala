package io.casperlabs.benchmarks

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption

import cats._
import cats.effect.{Sync, Timer}
import cats.implicits._
import cats.temp.par._
import io.casperlabs.client.{DeployRuntime, DeployService}
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey}
import io.casperlabs.crypto.codec.Base64
import io.casperlabs.crypto.signatures.SignatureAlgorithm
import io.casperlabs.shared.{FilesAPI, Log}

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}

object Benchmarks {

  /** Each round is many token transfer deploys from different accounts to single recipient
    * TODO: Remove Sync
    *  */
  def run[F[_]: Log: DeployService: Par: Timer: FilesAPI: Monad: Sync](
      outputStats: File,
      initialFundsPrivateKeyFile: File,
      initialFundsPublicKeyFile: File,
      accountsNum: Int = 250,
      roundsNum: Int = 100
  ): F[Unit] = {
    def readPrivateKey =
      FilesAPI[F].readString(initialFundsPrivateKeyFile.toPath, StandardCharsets.UTF_8).map {
        rawKey =>
          SignatureAlgorithm.Ed25519.tryParsePrivateKey(rawKey).get
      }

    def readPublicKey =
      FilesAPI[F].readString(initialFundsPublicKeyFile.toPath, StandardCharsets.UTF_8).map {
        rawKey =>
          SignatureAlgorithm.Ed25519.tryParsePublicKey(rawKey).get
      }

    def writeStatsFileHeader: F[Unit] =
      FilesAPI[F]
        .writeString(
          outputStats.toPath,
          "Deploy time, Propose time, Total time, Deploys/sec in propose\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE :: StandardOpenOption.TRUNCATE_EXISTING :: StandardOpenOption.WRITE :: Nil
        )

    def send(
        nonce: Long,
        recipientPublicKeyBase64: String,
        senderPrivateKey: PrivateKey,
        senderPublicKey: PublicKey,
        amount: Int
    ): F[Unit] = DeployRuntime.transfer[F](
      nonce = nonce,
      sessionCode = None,
      senderPublicKey = senderPublicKey,
      senderPrivateKey = senderPrivateKey,
      recipientPublicKeyBase64 = recipientPublicKeyBase64,
      amount = amount,
      exit = false,
      ignoreOutput = true
    )

    def createAccountKeyPair(): (Keys.PrivateKey, Keys.PublicKey) =
      SignatureAlgorithm.Ed25519.newKeyPair

    val senders: List[(Keys.PrivateKey, Keys.PublicKey)] =
      List.fill(accountsNum)(createAccountKeyPair())
    val recipient @ (_, recipientPublicKey): (Keys.PrivateKey, Keys.PublicKey) =
      createAccountKeyPair()
    val recipientBase64 = Base64.encode(recipientPublicKey)

    def initializeAccounts(
        initialFundsPrivateKey: PrivateKey,
        initialFundsPublicKey: PublicKey
    ): F[Unit] =
      for {
        _ <- Log[F].info("Initializing accounts...")
        _ <- (recipient :: senders).zipWithIndex.traverse {
              case ((_, pk), i) =>
                send(
                  nonce = i.toLong + 1L,
                  recipientPublicKeyBase64 = Base64.encode(pk),
                  senderPrivateKey = initialFundsPrivateKey,
                  senderPublicKey = initialFundsPublicKey,
                  amount = 100000
                )
            }
        _ <- propose
      } yield ()

    def oneRoundTransfer(nonce: Long): F[Unit] =
      for {
        _ <- Log[F].info("Sending deploys...")
        _ <- senders.parTraverse {
              case (sk, pk) =>
                send(
                  nonce = nonce,
                  recipientPublicKeyBase64 = recipientBase64,
                  senderPrivateKey = sk,
                  senderPublicKey = pk,
                  amount = 1
                )
            }
      } yield ()

    def propose: F[Unit] =
      for {
        _ <- Log[F].info("Proposing...")
        _ <- DeployRuntime.propose[F](
              exit = false,
              ignoreOutput = true
            )
      } yield ()

    def measure(task: F[Unit]): F[FiniteDuration] =
      for {
        start <- Timer[F].clock.monotonic(MILLISECONDS)
        _     <- task
        end   <- Timer[F].clock.monotonic(MILLISECONDS)
      } yield FiniteDuration(end - start, MILLISECONDS)

    def writeResults(
        deployTime: FiniteDuration,
        proposeTime: FiniteDuration,
        total: FiniteDuration,
        nonce: Long
    ): F[Unit] = {
      def format(fd: FiniteDuration): String = fd.toCoarsest.toString()
      val message =
        s"${format(deployTime)}, ${format(proposeTime)}, ${format(total)}, ${accountsNum / proposeTime.toSeconds}"
      FilesAPI[F].writeString(
        outputStats.toPath,
        message ++ "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.WRITE ::
          StandardOpenOption.APPEND :: Nil
      ) >> Log[F].info(s"Round: ${nonce - 1}: $message")
    }

    def round(nonce: Long): F[Unit] =
      for {
        _           <- Log[F].info(s"Starting new round ${nonce - 1}")
        deployTime  <- measure(oneRoundTransfer(nonce))
        proposeTime <- measure(propose)
        totalTime   = deployTime + proposeTime
        _           <- writeResults(deployTime, proposeTime, totalTime, nonce)
      } yield ()

    def rounds(n: Int): F[Unit] = {
      def loop(nonce: Long): F[Unit] =
        if (nonce == n) {
          Monad[F].unit
        } else {
          round(nonce).flatMap(_ => loop(nonce + 1))
        }

      for {
        _          <- Log[F].info("Running...")
        _          <- writeStatsFileHeader
        privateKey <- readPrivateKey
        publicKey  <- readPublicKey
        _          <- initializeAccounts(privateKey, publicKey)
        _          <- propose
        _          <- loop(1)
        _          <- Log[F].info("Done")
      } yield ()
    }

    rounds(roundsNum)
  }
}
