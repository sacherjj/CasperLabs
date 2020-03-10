package io.casperlabs.casper.dag

import cats.Functor

sealed trait ObservedValidatorBehavior[+A] extends Product with Serializable {
  def isEquivocated: Boolean
}

object ObservedValidatorBehavior {

  case class Honest[A](msg: A) extends ObservedValidatorBehavior[A] {
    override def isEquivocated: Boolean = false
  }

  case class Equivocated[A](m1: A, m2: A) extends ObservedValidatorBehavior[A] {
    override def isEquivocated: Boolean = true
  }

  case object Empty extends ObservedValidatorBehavior[Nothing] {
    override def isEquivocated: Boolean = false
  }

  implicit val functor: Functor[ObservedValidatorBehavior] =
    new Functor[ObservedValidatorBehavior] {
      def map[A, B](fa: ObservedValidatorBehavior[A])(f: A => B): ObservedValidatorBehavior[B] =
        fa match {
          case Honest(msg)         => Honest(f(msg))
          case Equivocated(m1, m2) => Equivocated(f(m1), f(m2))
          case Empty               => Empty
        }
    }
}
