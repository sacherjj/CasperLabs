package io.casperlabs.shared

import shapeless._

trait Merge[A] {
  def merge(l: A, r: A): A
}

trait LowPriorityMerge {
  implicit def optionMerge[A](implicit ev: A <:!< Product): Merge[Option[A]] =
    (l, r) => l.orElse(r)
}

trait MergeImplicits extends LowPriorityMerge {
  implicit def genericOptionMerge[A: Merge]: Merge[Option[A]] = (l, r) => {
    (l, r) match {
      case (Some(lV), Some(rV)) =>
        Some(Merge[A].merge(lV, rV))
      case _ => l.orElse(r)
    }
  }

  implicit def genericMerge[A, Repr <: HList](
      implicit gen: Generic.Aux[A, Repr],
      hlMerge: Lazy[Merge[Repr]]
  ) =
    new Merge[A] {
      override def merge(l: A, r: A): A = {
        val lGen = gen.to(l)
        val rGen = gen.to(r)
        val res  = hlMerge.value.merge(lGen, rGen)
        gen.from(res)
      }
    }

  implicit def hconsMerge[H, T <: HList](
      implicit headMerge: Lazy[Merge[H]],
      tailMerge: Lazy[Merge[T]]
  ) =
    new Merge[H :: T] {
      override def merge(l: H :: T, r: H :: T): H :: T = {
        val head = headMerge.value.merge(l.head, r.head)
        val tail = tailMerge.value.merge(l.tail, r.tail)
        head :: tail
      }
    }

  implicit def hnilMerge = new Merge[HNil] {
    override def merge(l: HNil, r: HNil): HNil = HNil
  }
}

object Merge extends MergeImplicits {
  def apply[A](implicit merge: Merge[A]) = merge
}
