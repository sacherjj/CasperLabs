package io.casperlabs.casper.validation

import io.casperlabs.casper.InvalidBlock

object Errors {
  // Wrapper for the tests that were originally outside the `attemptAdd` method
  // and meant the block was not getting saved.
  final case class DropErrorWrapper(status: InvalidBlock)     extends Exception
  final case class ValidateErrorWrapper(status: InvalidBlock) extends Exception(status.toString)
}
