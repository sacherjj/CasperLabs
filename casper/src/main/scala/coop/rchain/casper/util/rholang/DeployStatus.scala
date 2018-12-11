package coop.rchain.casper.util.rholang

sealed trait DeployStatus { self =>
  def isFailed: Boolean = self match {
    case _: Failed => true
    case _         => false
  }

  def isInternalError = self match {
    case _: InternalErrors => true
    case _                 => false
  }
}
final case object Succeeded                                extends DeployStatus
sealed trait Failed                                        extends DeployStatus
final case object UnknownFailure                           extends Failed
final case class UserErrors(errors: Vector[Throwable])     extends Failed
final case class InternalErrors(errors: Vector[Throwable]) extends Failed
