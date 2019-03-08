package io.casperlabs.comm

import io.casperlabs.shared.Resources
import java.net.ServerSocket

trait TestRuntime {

  def getFreePort: Int =
    Resources.withResource(new ServerSocket(0)) { s =>
      s.setReuseAddress(true)
      s.getLocalPort
    }

}
