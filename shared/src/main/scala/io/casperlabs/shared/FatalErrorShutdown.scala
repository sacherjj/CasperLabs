package io.casperlabs.shared

// Fatal error that should shut down the node.
case class FatalErrorShutdown(message: String) extends VirtualMachineError(message)
