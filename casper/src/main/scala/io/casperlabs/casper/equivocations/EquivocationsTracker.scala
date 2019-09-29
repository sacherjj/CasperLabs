package io.casperlabs.casper.equivocations

import io.casperlabs.casper.Estimator.Validator

// Stores the lowest rank of any base block, that is, a block from the equivocating validator which
// precedes the two or more blocks which equivocated by sharing the same sequence number.
final class EquivocationsTracker(private val map: Map[Validator, Long]) {
  def isEmpty: Boolean = map.isEmpty

  /**
    * Add a new equivocation record to EquivocationsTracker
    * @param validator validator who equivocated
    * @param rank the rank of the base block
    * @return
    */
  def updated(validator: Validator, rank: Long): EquivocationsTracker =
    get(validator) match {
      case Some(lowestBaseSeqNum) if rank < lowestBaseSeqNum =>
        // The validator equivocated again, and the rank is smaller than previously stored rank
        new EquivocationsTracker(map.updated(validator, rank))
      case None =>
        // The first time we detect the validator equivocated
        new EquivocationsTracker(map.updated(validator, rank))
      case _ =>
        // The validator equivocated again, but the rank is bigger than previously stored rank
        this
    }

  def get(validator: Validator): Option[Long] =
    map.get(validator)

  def contains(validator: Validator): Boolean =
    map.contains(validator)

  def keySet: Set[Validator] =
    map.keySet

  // Return the minimal rank of all base blocks
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
