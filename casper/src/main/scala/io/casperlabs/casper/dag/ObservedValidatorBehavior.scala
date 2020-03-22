package io.casperlabs.casper.dag

import cats.Functor

sealed trait ObservedValidatorBehavior[+A] extends Product with Serializable {
  def isEquivocated: Boolean
  def isEmpty: Boolean
  def isHonest: Boolean
}

object ObservedValidatorBehavior {

  case class Honest[A](msg: A) extends ObservedValidatorBehavior[A] {
    override def isEquivocated: Boolean = false
    override def isEmpty: Boolean       = false
    override def isHonest: Boolean      = true
  }

  case class Equivocated[A](m1: A, m2: A) extends ObservedValidatorBehavior[A] {
    override def isEquivocated: Boolean = true
    override def isEmpty: Boolean       = false
    override def isHonest: Boolean      = false
  }

  case object Empty extends ObservedValidatorBehavior[Nothing] {
    override def isEquivocated: Boolean = false
    override def isEmpty: Boolean       = true
    override def isHonest: Boolean      = false
  }

  def honest[A](a: A): ObservedValidatorBehavior[A]              = Honest(a)
  def equivocated[A](m1: A, m2: A): ObservedValidatorBehavior[A] = Equivocated(m1, m2)
  def empty[A]: ObservedValidatorBehavior[A]                     = Empty

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
