package io.casperlabs.casper

import cats._
import cats.implicits._
import cats.effect._
import cats.effect.concurrent._
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.shared.Time
import io.casperlabs.shared.Log
import io.casperlabs.metrics.Metrics
import scala.concurrent.duration._
import scala.util.control.NonFatal

/** Propose a block automatically whenever a timespan has elapsed or
  * we have more than a certain number of new deploys in the buffer. */
class AutoProposer[F[_]: Concurrent: Time: Log: Metrics: MultiParentCasperRef](
    checkInterval: FiniteDuration,
    maxInterval: FiniteDuration,
    maxCount: Int,
    blockApiLock: Semaphore[F]
) {

  private def run(): F[Unit] = {
    val maxElapsedMillis = maxInterval.toMillis

    def loop(
        // Deploys we saw at the last auto-proposal.
        prevDeploys: Set[Deploy],
        // Time we saw the first new deploy after an auto-proposal.
        startMillis: Long
    ): F[Unit] = {

      val snapshot = for {
        currentMillis <- Time[F].currentMillis
        casper        <- MultiParentCasperRef[F].get
        // NOTE: Currently the `remainingDeploys` method is private and quite inefficient
        // (easily goes back to Genesis), but in theory we could try to detect orphans and
        // propose again automatically.
        deploys <- casper.fold(Set.empty[Deploy].pure[F])(_.bufferedDeploys)
      } yield (currentMillis, currentMillis - startMillis, deploys, deploys diff prevDeploys)

      snapshot flatMap {
        // Reset time when we see a new deploy.
        case (currentMillis, _, _, newDeploys) if newDeploys.nonEmpty && startMillis == 0 =>
          Time[F].sleep(checkInterval) *>
            loop(prevDeploys, currentMillis)

        case (_, elapsedMillis, deploys, newDeploys)
            if newDeploys.nonEmpty && (elapsedMillis >= maxElapsedMillis || newDeploys.size >= maxCount) =>
          Log[F].info(
            s"Proposing block after ${elapsedMillis} ms and ${newDeploys.size} new deploys."
          ) *>
            tryPropose() *>
            loop(deploys, 0)

        case _ =>
          Time[F].sleep(checkInterval) *>
            loop(prevDeploys, startMillis)
      }
    }

    loop(Set.empty, 0) onError {
      case NonFatal(ex) =>
        Log[F].error(s"Auto-proposal stopped unexpectedly.", ex)
    }
  }

  private def tryPropose(): F[Unit] =
    BlockAPI.propose(blockApiLock).flatMap { blockHash =>
      Log[F].info(s"Proposed block ${PrettyPrinter.buildString(blockHash)}")
    } handleErrorWith {
      case NonFatal(ex) =>
        Log[F].error(s"Could not propose block.", ex)
    }
}

object AutoProposer {

  /** Start the proposal loop in the background. */
  def apply[F[_]: Concurrent: Time: Log: Metrics: MultiParentCasperRef](
      checkInterval: FiniteDuration,
      maxInterval: FiniteDuration,
      maxCount: Int,
      blockApiLock: Semaphore[F]
  ): Resource[F, AutoProposer[F]] =
    Resource[F, AutoProposer[F]] {
      for {
        ap    <- Sync[F].delay(new AutoProposer(checkInterval, maxInterval, maxCount, blockApiLock))
        fiber <- Concurrent[F].start(ap.run())
      } yield ap -> fiber.cancel
    }
}
