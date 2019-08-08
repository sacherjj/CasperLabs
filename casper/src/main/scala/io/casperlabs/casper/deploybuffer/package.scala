package io.casperlabs.casper

package object deploybuffer {
  type DeployStorage[F[_]] = DeployStorageWriter[F] with DeployStorageReader[F]
}
