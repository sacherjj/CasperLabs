package io.casperlabs.client
import io.casperlabs.casper.consensus
import io.casperlabs.casper.consensus.info
import simulacrum.typeclass

import scala.util.Either

@typeclass trait DeployService[F[_]] {
  def deploy(d: consensus.Deploy): F[Either[Throwable, String]]
  def propose(): F[Either[Throwable, String]]
  def showBlock(blockHash: String): F[Either[Throwable, String]]
  def showDeploys(blockHash: String): F[Either[Throwable, String]]
  def showDeploy(blockHash: String): F[Either[Throwable, String]]
  def showBlocks(depth: Int): F[Either[Throwable, String]]
  def visualizeDag(depth: Int, showJustificationLines: Boolean): F[Either[Throwable, String]]
  def queryState(
      blockHash: String,
      keyVariant: String,
      keyValue: String,
      path: String
  ): F[Either[Throwable, String]]
}
