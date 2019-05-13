package io.casperlabs.casper.util.execengine

import cats.kernel.Monoid
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

  implicit val OpMonoid: Monoid[Op] = new Monoid[Op] {
    def combine(a: Op, b: Op): Op = a + b
    def empty: Op                 = NoOp
  }

  implicit def OpMapMonoid[K]: Monoid[OpMap[K]] = new Monoid[OpMap[K]] {
    def combine(a: OpMap[K], b: OpMap[K]): OpMap[K] = a + b
    def empty: OpMap[K]                             = Map.empty[K, Op]
  }

  def fromIpc(o: ipc.Op): Option[Op] = o.opInstance match {
    case ipc.Op.OpInstance.Empty    => None
    case ipc.Op.OpInstance.Write(_) => Some(Write)
    case ipc.Op.OpInstance.Noop(_)  => Some(NoOp)
    case ipc.Op.OpInstance.Read(_)  => Some(Read)
    case ipc.Op.OpInstance.Add(_)   => Some(Add)
  }

  def fromTransform(t: ipc.Transform): Option[Op] = t.transformInstance match {
    case ipc.Transform.TransformInstance.Empty       => None
    case ipc.Transform.TransformInstance.Identity(_) => Some(Read)
    case ipc.Transform.TransformInstance.Write(_)    => Some(Write)
    // Transform failures should never arise because merging is total
    case ipc.Transform.TransformInstance.Failure(_) => None
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

  // Construct the map from the tuples, summing the Ops in the
  // case of duplicate keys (duplicate meaning appears more
  // than once in the seqence).
  def fromTuples[K](tuples: Seq[(K, Op)]): OpMap[K] =
    tuples.foldLeft(Map.empty[K, Op]) {
      case (acc, (k, op)) =>
        val curr = acc.getOrElse(k, NoOp)
        acc.updated(k, curr + op)
    }

  def fromIpcEntry(os: Seq[ipc.OpEntry]): OpMap[ipc.Key] = fromTuples(
    os.flatMap {
      case ipc.OpEntry(maybeKey, maybeOp) =>
        for {
          k  <- maybeKey
          o  <- maybeOp
          op <- Op.fromIpc(o)
        } yield (k, op)
    }
  )

  def fromTransforms(ts: Seq[ipc.TransformEntry]): OpMap[ipc.Key] = fromTuples(
    ts.flatMap {
      case ipc.TransformEntry(maybeKey, maybeTransform) =>
        for {
          k  <- maybeKey
          t  <- maybeTransform
          op <- Op.fromTransform(t)
        } yield (k, op)
    }
  )
}
