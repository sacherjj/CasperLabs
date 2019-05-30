package io.casperlabs.models

import scala.util.control.NoStackTrace

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

case class SmartContractEngineError(message: String) extends NoStackTrace

class ReplayException(msg: String) extends Exception(msg)

object DeployResult {
  def fromErrors(errors: Throwable): DeployResult =
    errors match {
      case _: SmartContractEngineError => InternalErrors(Vector(errors))
      case _                           => UserErrors(Vector(errors))
    }
}
