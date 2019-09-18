package io.casperlabs.casper.equivocations

import io.casperlabs.casper.Estimator.Validator

// Stores the lowest rank of any base block, that is, a block from the equivocating validator which
// precedes the two or more blocks which equivocated by sharing the same sequence number.
final class EquivocationsTracker(private val map: Map[Validator, Long]) {
  def isEmpty: Boolean = map.isEmpty

  def updated(validator: Validator, rank: Long): EquivocationsTracker =
    get(validator) match {
      case Some(lowestBaseSeqNum) if rank < lowestBaseSeqNum =>
        new EquivocationsTracker(map.updated(validator, rank))
      case None =>
        new EquivocationsTracker(map.updated(validator, rank))
      case _ =>
        this
    }

  def get(validator: Validator): Option[Long] =
    map.get(validator)

  def contains(validator: Validator): Boolean =
    map.contains(validator)

  def keySet: Set[Validator] =
    map.keySet

  def min: Option[Long] =
    if (isEmpty) {
      None
    } else {
      Some(map.values.min)
    }
}

object EquivocationsTracker {
  val empty: EquivocationsTracker = new EquivocationsTracker(Map.empty)
}
