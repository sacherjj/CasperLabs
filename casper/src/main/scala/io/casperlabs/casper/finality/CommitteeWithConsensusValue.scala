package io.casperlabs.casper.finality

import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.models.Weight
import io.casperlabs.shared.ByteStringPrettyPrinter._
import cats.syntax.show._

case class CommitteeWithConsensusValue(
    validator: Set[Validator],
    quorum: Weight,
    consensusValue: BlockHash
) {
  override def toString: String =
    s"CommitteeWithConsensusValue(" +
      s"${validator.map(_.show).mkString("{", ", ", "}")}, " +
      s"$quorum, ${consensusValue.show})"
}
