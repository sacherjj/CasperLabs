package io.casperlabs.catscontrib.laws

import cats.Eq
import cats.data.EitherT
import cats.effect.IO
import cats.effect.laws.discipline.arbitrary._
import cats.laws.discipline.arbitrary._
import cats.effect.laws.discipline.BracketTests
import cats.effect.laws.util.TestContext
import cats.effect.laws.util.TestInstances._
import cats.tests.CatsSuite
import org.scalacheck.{Arbitrary, Cogen, Gen}
import io.casperlabs.catscontrib.effect.implicits.bracketEitherTThrowable

class TestBracket extends CatsSuite {
  sealed trait MyErr
  case object Err1 extends MyErr
  case object Err2 extends MyErr
  case object Err3 extends MyErr

  type Effect[A] = EitherT[IO, MyErr, A]

  implicit val eqErr: Eq[MyErr] = (_, _) => true

  implicit def eqEitherT[A, B, F[_]](implicit eqE: Eq[F[Either[A, B]]]) = new Eq[EitherT[F, A, B]] {
    override def eqv(x: EitherT[F, A, B], y: EitherT[F, A, B]): Boolean = eqE.eqv(x.value, y.value)
  }

  implicit val ec = TestContext()

  implicit val genMyErr: Arbitrary[MyErr] = Arbitrary {
    Gen.oneOf(Seq(Err1, Err2, Err3))
  }

  implicit val cogenMyErr: Cogen[MyErr] = Cogen(_.hashCode())

  checkAll("Effect", BracketTests[Effect, Throwable].bracket[Int, String, Unit])
}
