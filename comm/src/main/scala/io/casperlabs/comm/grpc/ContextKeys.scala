package io.casperlabs.comm.grpc

import io.grpc.Context
import io.casperlabs.comm.auth.Principal

object ContextKeys {
  val Principal: Context.Key[Principal] =
    Context.key("auth.principal")
}
