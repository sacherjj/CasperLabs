package io.casperlabs.client
import io.casperlabs.casper.consensus
import io.casperlabs.casper.consensus.state.Value
import simulacrum.typeclass
@typeclass trait DeployService[F[_]] {
  def deploy(d: consensus.Deploy): F[String]
  def propose(): F[String]
  def showBlock(blockHash: String): F[String]
  def showDeploys(blockHash: String): F[String]
  def showDeploy(blockHash: String): F[String]
  def showBlocks(depth: Int): F[String]
  def visualizeDag(depth: Int, showJustificationLines: Boolean): F[String]
  def queryState(
      blockHash: String,
      keyVariant: String,
      keyValue: String,
      path: String
  ): F[Value]
}
