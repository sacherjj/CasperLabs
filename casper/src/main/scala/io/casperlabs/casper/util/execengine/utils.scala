package io.casperlabs.casper.util.execengine

import cats.Show
import io.casperlabs.ipc.DeployError
import io.casperlabs.ipc.DeployError.Value.{Empty, ExecError, GasError}

object utils {
  implicit val deployErrorsShow: Show[DeployError] = Show.show {
    _.value match {
      case Empty                                          => ""
      case GasError(DeployError.OutOfGasError())          => "OutOfGas"
      case ExecError(DeployError.ExecutionError(message)) => message
    }
  }
}
