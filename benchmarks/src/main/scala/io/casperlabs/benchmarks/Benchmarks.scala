package io.casperlabs.benchmarks

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption

import cats._
import cats.effect.{Sync, Timer}
import cats.implicits._
import io.casperlabs.client.{DeployRuntime, DeployService}
import io.casperlabs.client.configuration.DeployConfig
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey}
import io.casperlabs.crypto.codec.Base64
import io.casperlabs.crypto.signatures.SignatureAlgorithm
import io.casperlabs.shared.{FilesAPI, Log}

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}

object Benchmarks {

  /** Each round consists of many token transfer deploys from different accounts to single recipient
    * TODO: Remove Sync
    *  */
  def run[F[_]: Log: DeployService: Timer: FilesAPI: Monad: Sync](
      outputStats: File,
      initialFundsPrivateKeyFile: File,
      initialFundsPublicKeyFile: File,
      accountsNum: Int = 250,
      roundsNum: Int = 100,
      approximateTransferCost: Long = 10000000
  ): F[Unit] = {
    // TODO: Probably can cause overflow problems, for the time being it can stay as is.
    val initialFundsPerAccount = roundsNum * approximateTransferCost

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
        recipientPublicKey: PublicKey,
        senderPrivateKey: PrivateKey,
        senderPublicKey: PublicKey,
        amount: Long
    ): F[Unit] = DeployRuntime.transfer[F](
      deployConfig = DeployConfig.empty,
      senderPublicKey = senderPublicKey,
      senderPrivateKey = senderPrivateKey,
      recipientPublicKey = recipientPublicKey,
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

    def initializeAccounts(
        initialFundsPrivateKey: PrivateKey,
        initialFundsPublicKey: PublicKey
    ): F[Unit] =
      for {
        _ <- Log[F].info("Initializing accounts...")
        _ <- (recipient :: senders).traverse {
              case (_, pk) =>
                for {
                  _ <- send(
                        recipientPublicKey = pk,
                        senderPrivateKey = initialFundsPrivateKey,
                        senderPublicKey = initialFundsPublicKey,
                        amount = initialFundsPerAccount
                      )
                  blockHash <- propose(print = false)
                  _         <- checkSuccess(blockHash, 1)
                } yield ()
            }
      } yield ()

    def oneRoundTransfer(): F[Unit] =
      for {
        _ <- Log[F].info("Sending deploys...")
        _ <- senders.traverse {
              case (sk, pk) =>
                send(
                  recipientPublicKey = recipientPublicKey,
                  senderPrivateKey = sk,
                  senderPublicKey = pk,
                  amount = 1
                )
            }
      } yield ()

    def propose(print: Boolean): F[String] =
      for {
        _         <- Log[F].info("Proposing...").whenA(print)
        blockHash <- DeployService[F].propose().rethrow
      } yield blockHash

    def checkSuccess(blockHash: String, expectedDeployNum: Int): F[Unit] =
      for {
        blockInfo   <- DeployService[F].showBlock(blockHash).rethrow
        deployCount = blockInfo.getSummary.getHeader.deployCount
        _ <- Sync[F]
              .raiseError(
                new IllegalStateException(
                  s"Proposed block $blockInfo contains $deployCount!=$expectedDeployNum"
                )
              )
              .whenA(deployCount != expectedDeployNum)
        deployErrorCount = blockInfo.getStatus.getStats.deployErrorCount
        _ <- Sync[F]
              .raiseError(
                new IllegalStateException(
                  s"Proposed block $blockInfo contains $deployErrorCount!=0 failed deploys"
                )
              )
              .whenA(deployErrorCount != 0)
      } yield ()

    def measure[A](task: F[A]): F[(FiniteDuration, A)] =
      for {
        start <- Timer[F].clock.monotonic(MILLISECONDS)
        a     <- task
        end   <- Timer[F].clock.monotonic(MILLISECONDS)
      } yield (FiniteDuration(end - start, MILLISECONDS), a)

    def writeResults(
        deployTime: FiniteDuration,
        proposeTime: FiniteDuration,
        total: FiniteDuration,
        round: Long
    ): F[Unit] = {
      def format(fd: FiniteDuration): String = fd.toCoarsest.toString()
      val message =
        s"${format(deployTime)}, ${format(proposeTime)}, ${format(total)}, ${((accountsNum * 1000.0) / proposeTime.toMillis.toDouble)
          .formatted("%1.2f")}"
      FilesAPI[F].writeString(
        outputStats.toPath,
        message ++ "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.WRITE ::
          StandardOpenOption.APPEND :: Nil
      ) >> Log[F].info(s"${round - 1 -> "round"}: $message")
    }

    def round(round: Long): F[Unit] =
      for {
        _                        <- Log[F].info(s"Starting new round: $round")
        (deployTime, _)          <- measure(oneRoundTransfer())
        (proposeTime, blockHash) <- measure(propose(print = true))
        _                        <- checkSuccess(blockHash, accountsNum)
        totalTime                = deployTime + proposeTime
        _                        <- writeResults(deployTime, proposeTime, totalTime, round)
      } yield ()

    def rounds(n: Int): F[Unit] = {
      def loop(roundNum: Long): F[Unit] =
        if (roundNum == n) {
          Monad[F].unit
        } else {
          round(roundNum).flatMap(_ => loop(roundNum + 1))
        }

      for {
        _          <- Log[F].info("Running...")
        _          <- writeStatsFileHeader
        privateKey <- readPrivateKey
        publicKey  <- readPublicKey
        _          <- initializeAccounts(privateKey, publicKey)
        _          <- loop(1)
        _          <- Log[F].info("Done")
      } yield ()
    }

    rounds(roundsNum)
  }
}
