package io.casperlabs.casper.util.rholang
import cats.effect.Resource
import io.casperlabs.casper.Resources.mkRuntime
import io.casperlabs.shared.StoreType
import monix.eval.Task
import monix.execution.Scheduler

object Resources {
  def mkRuntimeManager(
      prefix: String,
      storageSize: Int = 1024 * 1024,
      storeType: StoreType = StoreType.LMDB
  )(implicit scheduler: Scheduler): Resource[Task, RuntimeManager] =
    mkRuntime(prefix)
      .flatMap { smartContractsApi =>
        Resource.pure(RuntimeManager.fromSmartContractApi(smartContractsApi))
      }
}
