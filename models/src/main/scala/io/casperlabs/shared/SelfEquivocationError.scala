package io.casperlabs.shared

case class SelfEquivocationError(message: String) extends Throwable(message)
