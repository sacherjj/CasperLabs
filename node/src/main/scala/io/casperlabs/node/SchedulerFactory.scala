package io.casperlabs.node

import java.util.concurrent._
import monix.execution.Scheduler
import monix.execution.schedulers.{ExecutorScheduler, SchedulerService}
import monix.execution.{Features, Scheduler, SchedulerCompanion, UncaughtExceptionReporter}
import monix.execution.{ExecutionModel => ExecModel}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._

/** Replicate some of the methods from monix to create Scheduler instances
  * but make the thread pools available for metrics reporting.
  */
class SchedulerFactory private (
    poolMap: TrieMap[String, SchedulerFactory.Pool],
    reporter: UncaughtExceptionReporter
) {
  import SchedulerFactory._

  /** Collect statistics for all pools created by this factory. */
  def getStats: Map[String, PoolStats] =
    poolMap.toSeq.map {
      case (name, pool) => name -> pool.getStats
    }.toMap

  def fixedPool(
      name: String,
      poolSize: Int
  ): SchedulerService =
    register(
      name,
      Scheduler.fixedPool(name, poolSize, reporter = reporter).asInstanceOf[ExecutorScheduler]
    )

  def forkJoin(
      parallelism: Int,
      maxThreads: Int,
      name: String
  ): SchedulerService =
    register(
      name,
      ExecutorScheduler
        .forkJoinDynamic(
          name,
          parallelism,
          maxThreads,
          daemonic = true,
          reporter = reporter,
          executionModel = ExecModel.Default
        )
    )

  def cached(
      name: String,
      minThreads: Int,
      maxThreads: Int,
      keepAliveTime: FiniteDuration = 60.seconds
  ): SchedulerService =
    register(
      name,
      Scheduler
        .cached(name, minThreads, maxThreads, keepAliveTime, reporter = reporter)
        .asInstanceOf[ExecutorScheduler]
    )

  private def register(name: String, scheduler: ExecutorScheduler): ExecutorScheduler = {
    val pool = scheduler.executor match {
      case executor: ForkJoinPool =>
        Pool {
          PoolStats(
            poolSize = executor.getPoolSize(),
            activeThreadCount = executor.getActiveThreadCount(),
            taskQueueSize = executor.getQueuedSubmissionCount().toLong + executor
              .getQueuedTaskCount()
          )
        }

      case executor: ScheduledThreadPoolExecutor =>
        Pool {
          PoolStats(
            poolSize = executor.getPoolSize(),
            activeThreadCount = executor.getActiveCount(),
            taskQueueSize = executor.getQueue().size().toLong
          )
        }

      case executor: ThreadPoolExecutor =>
        Pool {
          PoolStats(
            poolSize = executor.getPoolSize(),
            activeThreadCount = executor.getActiveCount(),
            taskQueueSize = executor.getQueue().size().toLong
          )
        }

      case other =>
        throw new IllegalArgumentException(
          s"Thread pool '$name' has an unknown executor type: ${other.getClass.getSimpleName}"
        )
    }

    poolMap.put(name, pool)

    scheduler
  }
}

object SchedulerFactory {
  def apply(
      reporter: UncaughtExceptionReporter = UncaughtExceptionReporter.default
  ): SchedulerFactory =
    new SchedulerFactory(TrieMap.empty[String, Pool], reporter)

  trait Pool {
    def getStats: PoolStats
  }
  object Pool {
    def apply(thunk: => PoolStats): Pool = new Pool {
      override def getStats = thunk
    }
  }

  case class PoolStats(
      poolSize: Int,
      activeThreadCount: Int,
      taskQueueSize: Long
  )
}
