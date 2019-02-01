package io.casperlabs.catscontrib

import cats.{Monad, Monoid}
import cats.implicits._

import scala.math.Ordering

object ListContrib {
  def sortBy[A, K: Monoid](list: List[A], map: collection.Map[A, K])(
      implicit ord: Ordering[K]
  ): List[A] =
    list.sortBy(map.getOrElse(_, Monoid[K].empty))(ord)

  // From https://hygt.github.io/2018/08/05/Cats-findM-collectFirstM.html
  def findM[G[_], A](list: List[A], p: A => G[Boolean])(implicit G: Monad[G]): G[Option[A]] =
    list.tailRecM[G, Option[A]] {
      case head :: tail =>
        p(head).map {
          case true  => Some(head).asRight[List[A]]
          case false => tail.asLeft[Option[A]]
        }
      case Nil => G.pure(None.asRight[List[A]])
    }

  def filterM[G[_], A](list: List[A], p: A => G[Boolean])(implicit G: Monad[G]): G[List[A]] =
    (list, List.empty[A]).tailRecM[G, List[A]] {
      case (head :: tail, acc) =>
        p(head).map {
          case true  => (tail, head :: acc).asLeft[List[A]]
          case false => (tail, acc).asLeft[List[A]]
        }
      case (Nil, acc) =>
        G.pure(acc.asRight[(List[A], List[A])])
    }
}
