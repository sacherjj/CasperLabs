package io.casperlabs.casper.util.execengine

import cats.Show
import io.casperlabs.ipc.DeployError
import io.casperlabs.ipc.DeployError.DeployErrors

object utils {
  implicit val deployErrorsShow: Show[DeployError] = Show.show {
    _.deployErrors match {
      case DeployErrors.Empty                                   => ""
      case DeployErrors.GasErr(DeployError.OutOfGasError())     => "OutOfGas"
      case DeployErrors.WasmErr(DeployError.WasmError(message)) => message
    }
  }
}
