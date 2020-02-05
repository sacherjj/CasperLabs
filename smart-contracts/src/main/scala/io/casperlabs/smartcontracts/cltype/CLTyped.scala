package io.casperlabs.smartcontracts.cltype

import simulacrum.typeclass

@typeclass trait CLTyped[T] {
  def clType: CLType
}

object CLTyped {
  implicit val clTypedBoolean: CLTyped[Boolean] = new CLTyped[Boolean] {
    override def clType: CLType = CLType.Bool
  }

  implicit val clTypedInt: CLTyped[Int] = new CLTyped[Int] {
    override def clType: CLType = CLType.I32
  }

  implicit val clTypedLong: CLTyped[Long] = new CLTyped[Long] {
    override def clType: CLType = CLType.I64
  }

  implicit val clTypedByte: CLTyped[Byte] = new CLTyped[Byte] {
    override def clType: CLType = CLType.U8
  }

  implicit val clTypedBigInt: CLTyped[BigInt] = new CLTyped[BigInt] {
    override def clType: CLType = CLType.U512
  }

  implicit val clTypedUnit: CLTyped[Unit] = new CLTyped[Unit] {
    override def clType: CLType = CLType.Unit
  }

  implicit val clTypedString: CLTyped[String] = new CLTyped[String] {
    override def clType: CLType = CLType.String
  }

  implicit def clTypedOption[A: CLTyped]: CLTyped[Option[A]] = new CLTyped[Option[A]] {
    override def clType: CLType = CLType.Option(CLTyped[A].clType)
  }

  implicit def clTypedList[A: CLTyped]: CLTyped[Seq[A]] = new CLTyped[Seq[A]] {
    override def clType: CLType = CLType.List(CLTyped[A].clType)
  }

  implicit def clTypedEither[A: CLTyped, B: CLTyped]: CLTyped[Either[A, B]] =
    new CLTyped[Either[A, B]] {
      override def clType: CLType = CLType.Result(ok = CLTyped[B].clType, err = CLTyped[A].clType)
    }

  implicit def CLTypedMap[K: CLTyped, V: CLTyped]: CLTyped[Map[K, V]] =
    new CLTyped[Map[K, V]] {
      override def clType: CLType = CLType.Map(key = CLTyped[K].clType, value = CLTyped[V].clType)
    }

  implicit def CLTypedTuple1[T1: CLTyped]: CLTyped[Tuple1[T1]] =
    new CLTyped[Tuple1[T1]] {
      override def clType: CLType = CLType.Tuple1(CLTyped[T1].clType)
    }

  implicit def CLTypedTuple2[T1: CLTyped, T2: CLTyped]: CLTyped[(T1, T2)] =
    new CLTyped[(T1, T2)] {
      override def clType: CLType = CLType.Tuple2(CLTyped[T1].clType, CLTyped[T2].clType)
    }

  implicit def CLTypedTuple3[T1: CLTyped, T2: CLTyped, T3: CLTyped]: CLTyped[(T1, T2, T3)] =
    new CLTyped[(T1, T2, T3)] {
      override def clType: CLType =
        CLType.Tuple3(CLTyped[T1].clType, CLTyped[T2].clType, CLTyped[T3].clType)
    }
}
