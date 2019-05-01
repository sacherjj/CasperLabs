package io.casperlabs.casper.util.execengine

import io.casperlabs.ipc

import Op.{Add, NoOp, Read, Write}

sealed trait Op { self =>
  def +(other: Op): Op = (self, other) match {
    case (a, NoOp)    => a
    case (NoOp, b)    => b
    case (Read, Read) => Read
    case (Add, Add)   => Add
    case _            => Write
  }

  // commutativity check
  def ~(other: Op): Boolean = (self, other) match {
    case (_, NoOp)    => true
    case (NoOp, _)    => true
    case (Read, Read) => true
    case (Add, Add)   => true
    case _            => false
  }
}

object Op {
  case object NoOp  extends Op
  case object Write extends Op
  case object Read  extends Op
  case object Add   extends Op

  def fromTransform(t: ipc.Transform): Option[Op] = t.transformInstance match {
    case ipc.Transform.TransformInstance.Empty       => None
    case ipc.Transform.TransformInstance.Identity(_) => Some(Read)
    case ipc.Transform.TransformInstance.Write(_)    => Some(Write)
    // Failures have the same commutativity properties as write (commute with nothing)
    case ipc.Transform.TransformInstance.Failure(_) => Some(Write)
    case _                                          => Some(Add) // We treat all types of addition the same (for now)
  }

  type OpMap[Key] = Map[Key, Op]
  implicit class OpMapAddComm[Key](val self: OpMap[Key]) {
    def +(other: OpMap[Key]): OpMap[Key] = {
      val allKeys = self.keySet union other.keySet
      allKeys.map(k => k -> (self.getOrElse(k, NoOp) + other.getOrElse(k, NoOp))).toMap
    }

    def ~(other: OpMap[Key]): Boolean = {
      val commonKeys = self.keySet intersect other.keySet
      commonKeys.forall(k => self(k) ~ other(k))
    }
  }

  def fromTransforms(ts: Seq[ipc.TransformEntry]): OpMap[ipc.Key] =
    ts.flatMap {
        case ipc.TransformEntry(maybeKey, maybeTransform) =>
          for {
            k  <- maybeKey
            t  <- maybeTransform
            op <- Op.fromTransform(t)
          } yield (k, op)
      }
      // Construct the map from the tuples, summing the Ops in the
      // case of duplicate keys (duplicate meaning appears more
      // than once in the seqence).
      .foldLeft(Map.empty[ipc.Key, Op]) {
        case (acc, (k, op)) =>
          val curr = acc.getOrElse(k, NoOp)
          acc.updated(k, curr + op)
      }
}
