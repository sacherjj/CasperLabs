package io.casperlabs.casper.util.rholang
import cats.effect.Resource
import io.casperlabs.casper.util.Resources.mkRuntime
import io.casperlabs.shared.StoreType
import monix.eval.Task
import monix.execution.Scheduler

object Resources {
  def mkRuntimeManager(
      prefix: String
  )(implicit scheduler: Scheduler): Resource[Task, RuntimeManager[Task]] =
    mkRuntime(prefix)
      .flatMap { smartContractsApi =>
        Resource.pure(RuntimeManager.fromSmartContractApi(smartContractsApi))
      }
}
