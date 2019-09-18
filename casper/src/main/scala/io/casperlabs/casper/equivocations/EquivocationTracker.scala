package io.casperlabs.casper.equivocations

import io.casperlabs.casper.Estimator.Validator

// Stores the lowest rank of any base block, that is, a block from the equivocating validator which
// precedes the two or more blocks which equivocated by sharing the same sequence number.
class EquivocationTracker(private val map: Map[Validator, Long]) {
  def isEmpty: Boolean = map.isEmpty

  def updated(validator: Validator, rank: Long): EquivocationTracker =
    new EquivocationTracker(map.updated(validator, rank))

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

object EquivocationTracker {
  val empty: EquivocationTracker = new EquivocationTracker(Map.empty)
}
