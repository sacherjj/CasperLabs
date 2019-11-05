package io.casperlabs.casper

sealed trait BlockStatus {
  val inDag: Boolean
}

final case object Processing extends BlockStatus {
  override val inDag: Boolean = false
}
final case object Processed extends BlockStatus {
  override val inDag: Boolean = true
}

final case class UnexpectedBlockException(ex: Throwable) extends BlockStatus {
  override val inDag: Boolean = false
}

sealed trait ValidBlock extends BlockStatus {
  override val inDag: Boolean = true
}
sealed trait InvalidBlock extends BlockStatus {
  override val inDag: Boolean = false
}
sealed trait Slashable

final case object Valid extends ValidBlock

final case object EquivocatedBlock extends InvalidBlock {
  override val inDag: Boolean = true
}
final case object SelfEquivocatedBlock extends InvalidBlock {
  override val inDag: Boolean = true
}
final case object InvalidUnslashableBlock extends InvalidBlock
final case object MissingBlocks           extends InvalidBlock

final case object InvalidBlockNumber     extends InvalidBlock with Slashable
final case object InvalidRepeatDeploy    extends InvalidBlock with Slashable
final case object InvalidParents         extends InvalidBlock with Slashable
final case object InvalidSequenceNumber  extends InvalidBlock with Slashable
final case object InvalidPrevBlockHash   extends InvalidBlock with Slashable
final case object InvalidChainName       extends InvalidBlock with Slashable
final case object NeglectedInvalidBlock  extends InvalidBlock with Slashable
final case object InvalidTransaction     extends InvalidBlock with Slashable
final case object InvalidPreStateHash    extends InvalidBlock with Slashable
final case object InvalidPostStateHash   extends InvalidBlock with Slashable
final case object InvalidBondsCache      extends InvalidBlock with Slashable
final case object InvalidBlockHash       extends InvalidBlock with Slashable
final case object InvalidDeployCount     extends InvalidBlock with Slashable
final case object InvalidDeployHash      extends InvalidBlock with Slashable
final case object InvalidDeploySignature extends InvalidBlock with Slashable
final case object InvalidDeployHeader    extends InvalidBlock with Slashable
final case object InvalidDeployChainName extends InvalidBlock with Slashable
final case object SwimlaneMerged         extends InvalidBlock with Slashable
final case object DeployDependencyNotMet extends InvalidBlock with Slashable
final case object DeployExpired          extends InvalidBlock with Slashable
final case object DeployFromFuture       extends InvalidBlock with Slashable

// Raised only for Ballot message type when parents.size != 1
final case object InvalidTargetHash extends InvalidBlock

object BlockStatus {
  val valid: BlockStatus      = Valid
  val processing: BlockStatus = Processing
  val processed: BlockStatus  = Processed
}
