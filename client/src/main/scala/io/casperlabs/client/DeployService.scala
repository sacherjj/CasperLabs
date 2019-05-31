package io.casperlabs.client
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.consensus
import simulacrum.typeclass

import scala.util.Either

@typeclass trait DeployService[F[_]] {
  def deploy(d: consensus.Deploy): F[Either[Throwable, String]]
  def propose(): F[Either[Throwable, String]]
  def showBlock(blockHash: String): F[Either[Throwable, String]]
  def showBlocks(depth: Int): F[Either[Throwable, String]]
  def visualizeDag(depth: Int, showJustificationLines: Boolean): F[Either[Throwable, String]]
  def queryState(q: QueryStateRequest): F[Either[Throwable, String]]
}
