package io.casperlabs.casper

import cats._
import cats.implicits._
import cats.effect._
import cats.effect.concurrent._
import io.casperlabs.casper.protocol.DeployData
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.shared.Time
import io.casperlabs.shared.Log
import io.casperlabs.metrics.Metrics
import scala.concurrent.duration._
import scala.util.control.NonFatal

/** Propose a block automatically whenever a timespan has elapsed or
  * we have more than a certain number of deploys in the buffer. */
class AutoProposer[F[_]: Concurrent: Time: Timer: Log: Metrics: MultiParentCasperRef](
    checkInterval: FiniteDuration,
    maxInterval: FiniteDuration,
    maxCount: Int,
    blockApiLock: Semaphore[F]
) {

  private val maxElapsedMillis = maxInterval.toMillis
  private val noDeploys        = Set.empty[DeployData]

  private def run(): F[Unit] = {
    def loop(prevDeploys: Set[DeployData], lastProposeMillis: Long): F[Unit] = {

      val snapshot = for {
        currentMillis <- Time[F].currentMillis
        casper        <- MultiParentCasperRef[F].get
        // NOTE: Currently the `remainingDeploys` method is private and quite inefficient
        // (easily goes back to Genesis), but in theory we could try to detect orphans and
        // propose again automatically.
        deploys <- casper.fold(noDeploys.pure[F])(_.bufferedDeploys)
      } yield (currentMillis, currentMillis - lastProposeMillis, deploys, deploys diff prevDeploys)

      snapshot flatMap {
        case (currentMillis, elapsedMillis, deploys, newDeploys)
            if newDeploys.nonEmpty && (elapsedMillis >= maxElapsedMillis || newDeploys.size >= maxCount) =>
          Log[F].info(
            s"Automatically proposing block after ${elapsedMillis} ms and ${newDeploys.size} new deploys."
          ) *>
            BlockAPI.createBlock(blockApiLock) *>
            loop(deploys, currentMillis)

        case _ =>
          Timer[F].sleep(checkInterval) *>
            loop(prevDeploys, lastProposeMillis)
      }
    }

    Time[F].currentMillis flatMap { ms =>
      loop(noDeploys, ms) onError {
        case NonFatal(ex) =>
          Log[F].error(s"Error during auto-proposal of blocks.", ex)
      }
    }
  }
}

object AutoProposer {

  /** Start the proposal loop in the background. */
  def apply[F[_]: Concurrent: Time: Timer: Log: Metrics: MultiParentCasperRef](
      checkInterval: FiniteDuration,
      maxInterval: FiniteDuration,
      maxCount: Int,
      blockApiLock: Semaphore[F]
  ): Resource[F, Unit] =
    Resource[F, Unit] {
      val ap = new AutoProposer(checkInterval, maxInterval, maxCount, blockApiLock)
      Concurrent[F].start(ap.run()) map { fiber =>
        () -> fiber.cancel
      }
    }
}
