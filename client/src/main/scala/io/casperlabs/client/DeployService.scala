package io.casperlabs.client
import io.casperlabs.casper.protocol.{BlockMessage, BlockQuery, BlocksQuery, DeployData}
import simulacrum.typeclass

@typeclass trait DeployService[F[_]] {
  def deploy(d: DeployData): F[Either[Throwable, String]]
  def createBlock(): F[Either[Throwable, String]] //create block and add to Casper internal state
  def showBlock(q: BlockQuery): F[Either[Throwable, String]]
  def showBlocks(q: BlocksQuery): F[Either[Throwable, String]]
  def addBlock(b: BlockMessage): F[Either[Throwable, String]]
}
