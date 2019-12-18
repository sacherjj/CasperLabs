package io.casperlabs.casper

import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.MultiParentCasperImpl.Broadcaster
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.storage.deploy.{DeployStorage, DeployStorageReader}
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.{Log, Time}

import scala.concurrent.duration._
import scala.util.control.NonFatal

/** Propose a block automatically whenever a timespan has elapsed or
  * we have more than a certain number of new deploys in the buffer. */
class AutoProposer[F[_]: Concurrent: Time: Log: Metrics: MultiParentCasperRef: DeployStorage: Broadcaster](
    checkInterval: FiniteDuration,
    ballotInterval: FiniteDuration,
    accInterval: FiniteDuration,
    accCount: Int,
    blockApiLock: Semaphore[F]
) {
  val accIntervalMillis    = accInterval.toMillis
  val ballotIntervalMillis = ballotInterval.toMillis

  private def run(lastProposeMillis: Long): F[Unit] = {
    def loop(
        // Deploys we tried to propose last time.
        prevDeploys: Set[ByteString],
        // Time we saw the first new deploys after an auto-proposal.
        startMillis: Long,
        // Time we proposed a block.
        lastProposeMillis: Long
    ): F[Unit] = {

      val snapshot = for {
        currentMillis <- Time[F].currentMillis
        deploys       <- DeployStorageReader[F].readPendingHashes.map(_.toSet)
      } yield (currentMillis, deploys)

      snapshot flatMap {
        // Reset time when we see a new deploy.
        case (currentMillis, deploys) if deploys.nonEmpty && startMillis == 0 =>
          Time[F].sleep(checkInterval) >> loop(prevDeploys, currentMillis, lastProposeMillis)

        case (currentMillis, deploys)
            if deploys.nonEmpty
              && deploys != prevDeploys
              && (currentMillis - startMillis >= accIntervalMillis || deploys.size >= accCount) =>
          Log[F].info(
            s"Proposing a block after ${currentMillis - startMillis} ms with ${deploys.size} pending deploys."
          ) *>
            tryPropose(false) >>= { hasProposed =>
            loop(deploys, 0, if (hasProposed) currentMillis else lastProposeMillis)
          }

        case (currentMillis, deploys)
            if currentMillis - lastProposeMillis >= ballotIntervalMillis =>
          Log[F].info(
            s"Proposing a block or ballot after ${lastProposeMillis - startMillis} ms."
          ) *>
            tryPropose(true) >>= { hasProposed =>
            loop(deploys, 0, if (hasProposed) currentMillis else lastProposeMillis)
          }

        case _ =>
          Time[F].sleep(checkInterval) >> loop(prevDeploys, startMillis, lastProposeMillis)
      }
    }

    loop(Set.empty, 0, lastProposeMillis) onError {
      case NonFatal(ex) =>
        Log[F].error(s"Auto-proposal stopped unexpectedly: $ex")
    }
  }

  /** Try to propose a block or ballot. Return true if anything was created. */
  private def tryPropose(canCreateBallot: Boolean): F[Boolean] =
    BlockAPI.propose(blockApiLock, canCreateBallot).flatMap { blockHash =>
      Log[F].info(s"Proposed ${PrettyPrinter.buildString(blockHash) -> "block"}").as(true)
    } handleErrorWith {
      case NonFatal(ex) =>
        Log[F].error(s"Could not propose block: $ex").as(false)
    }
}

object AutoProposer {

  /** Start the proposal loop in the background. */
  def apply[F[_]: Concurrent: Time: Log: Metrics: MultiParentCasperRef: DeployStorage: Broadcaster](
      checkInterval: FiniteDuration,
      ballotInterval: FiniteDuration,
      accInterval: FiniteDuration,
      accCount: Int,
      blockApiLock: Semaphore[F]
  ): Resource[F, AutoProposer[F]] =
    Resource[F, AutoProposer[F]] {
      for {
        ap <- Sync[F].delay(
               new AutoProposer(checkInterval, ballotInterval, accInterval, accCount, blockApiLock)
             )
        // We could retrieve the time we last proposed a block from storage
        // but it would likely be empty or older than the ballot interval
        // and just add complexity to the startup.
        start <- Time[F].currentMillis
        fiber <- Concurrent[F].start(ap.run(start))
      } yield ap -> fiber.cancel
    }
}
