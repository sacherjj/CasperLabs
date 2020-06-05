package io.casperlabs.node.diagnostics

import cats._
import cats.implicits._
import cats.effect.implicits._
import cats.effect.{Concurrent, Resource, Timer}
import com.zaxxer.hikari.metrics.{IMetricsTracker, MetricsTrackerFactory, PoolStats}
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.metrics.Metrics
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._

class HikariMetricsTrackerFactory private (
    trackerMap: TrieMap[String, HikariMetricsTrackerFactory.Tracker]
)(
    implicit metricsId: Metrics[Id]
) extends MetricsTrackerFactory {
  implicit val metricsSource = Metrics.BaseSource / "database"

  override def create(poolName: String, poolStats: PoolStats): IMetricsTracker = {
    val tracker = new HikariMetricsTrackerFactory.Tracker {
      override def getStats =
        poolStats

      override def recordConnectionAcquiredNanos(elapsedAcquiredNanos: Long): Unit =
        Metrics[Id].record(s"$poolName-conn-acquired-millis", elapsedAcquiredNanos / 1000000)

      override def recordConnectionCreatedMillis(connectionCreatedMillis: Long): Unit =
        Metrics[Id].record(s"$poolName-conn-created-millis", connectionCreatedMillis)

      override def recordConnectionUsageMillis(elapsedBorrowedMillis: Long): Unit =
        Metrics[Id].record(s"$poolName-conn-used-millis", elapsedBorrowedMillis)

      override def recordConnectionTimeout(): Unit =
        Metrics[Id].incrementCounter(s"$poolName-conn-timeout")

      override def close(): Unit =
        trackerMap.remove(poolName)
    }
    Metrics[Id].incrementCounter(s"$poolName-conn-timeout", 0)
    trackerMap.put(poolName, tracker)
    tracker
  }

  def periodicPoolMetrics[F[_]: Concurrent: Timer: Metrics](
      updatePeriod: FiniteDuration = 15.seconds
  ): Resource[F, F[Unit]] = {
    val update = for {
      _ <- trackerMap.toList.traverse {
            case (name, tracker) =>
              val stats = tracker.getStats
              for {
                _ <- Metrics[F]
                      .setGauge(s"$name-total-conn-count", stats.getTotalConnections().toLong)
                _ <- Metrics[F]
                      .setGauge(s"$name-active-conn-count", stats.getActiveConnections().toLong)
                _ <- Metrics[F]
                      .setGauge(s"$name-pending-thread-count", stats.getPendingThreads().toLong)
              } yield ()
          }
      _ <- Timer[F].sleep(updatePeriod)
    } yield ()

    update.forever.background
  }
}

object HikariMetricsTrackerFactory {
  trait Tracker extends IMetricsTracker {
    def getStats: PoolStats
  }

  def apply()(implicit metricsId: Metrics[Id]): HikariMetricsTrackerFactory =
    new HikariMetricsTrackerFactory(TrieMap.empty)
}
