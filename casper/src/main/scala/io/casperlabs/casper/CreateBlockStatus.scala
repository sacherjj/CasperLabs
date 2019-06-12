package io.casperlabs.casper

import cats.Applicative
import cats.implicits._
import io.casperlabs.casper.consensus.Block

sealed trait CreateBlockStatus
sealed trait NoBlock             extends CreateBlockStatus
case class Created(block: Block) extends CreateBlockStatus

case class InternalDeployError(ex: Throwable) extends NoBlock
case object ReadOnlyMode                      extends NoBlock
case object NoNewDeploys                      extends NoBlock

object CreateBlockStatus {
  def created(block: Block): CreateBlockStatus              = Created(block)
  def internalDeployError(ex: Throwable): CreateBlockStatus = InternalDeployError(ex)
  def readOnlyMode: CreateBlockStatus                       = ReadOnlyMode
  def noNewDeploys: CreateBlockStatus                       = NoNewDeploys
}
