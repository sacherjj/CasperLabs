package io.casperlabs.models

sealed trait DeployResult { self =>
  def isFailed: Boolean = self match {
    case _: Failed => true
    case _         => false
  }

  def isInternalError: Boolean = self match {
    case _: InternalErrors => true
    case _                 => false
  }
}
final case object Succeeded                                extends DeployResult
sealed trait Failed                                        extends DeployResult
final case object UnknownFailure                           extends Failed
final case class UserErrors(errors: Vector[Throwable])     extends Failed
final case class InternalErrors(errors: Vector[Throwable]) extends Failed

final class SmartContractEngineError(message: String) extends Throwable(message) {
  def this(message: String, cause: Throwable) {
    this(message)
    initCause(cause)
  }
}

class ReplayException(msg: String) extends Exception(msg)

object DeployResult {
  def fromErrors(errors: Throwable): DeployResult =
    errors match {
      case _: SmartContractEngineError => InternalErrors(Vector(errors))
      case _                           => UserErrors(Vector(errors))
    }
}
