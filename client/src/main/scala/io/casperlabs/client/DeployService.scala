package io.casperlabs.client
import io.casperlabs.casper.consensus
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.casper.consensus.state.StoredValueInstance
import simulacrum.typeclass

import scala.util.Either
import scala.concurrent.duration._

@typeclass trait DeployService[F[_]] {
  def deploy(
      d: consensus.Deploy
  ): F[Either[Throwable, String]]
  def propose(): F[Either[Throwable, String]]
  def showBlock(
      blockHash: String
  ): F[Either[Throwable, BlockInfo]]
  def showDeploys(
      blockHash: String,
      bytesStandard: Boolean,
      json: Boolean
  ): F[Either[Throwable, String]]
  def showDeploy(
      deployHash: String,
      bytesStandard: Boolean,
      json: Boolean,
      waitForProcessed: Boolean,
      timeoutSeconds: FiniteDuration
  ): F[Either[Throwable, String]]
  def showBlocks(depth: Int, bytesStandard: Boolean, json: Boolean): F[Either[Throwable, String]]
  def visualizeDag(depth: Int, showJustificationLines: Boolean): F[Either[Throwable, String]]
  def queryState(
      blockHash: String,
      keyVariant: String,
      keyValue: String,
      path: String
  ): F[Either[Throwable, StoredValueInstance]]
}
