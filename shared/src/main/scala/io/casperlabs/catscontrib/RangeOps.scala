package io.casperlabs.catscontrib

import cats.implicits._
import cats.Monad
import scala.collection.immutable.Range

object RangeOps {

  /**
    * Defining `foldM` for `Range`. We cannot extend `cats.Foldable` because `Range` has no type parameter.
    */
  implicit class FoldM(range: Range) {
    def foldM[G[_]: Monad, B](z: B)(f: (B, Int) => G[B]): G[B] = range.foldLeft(z.pure[G]) {
      case (acc, i) => acc.flatMap(b => f(b, i))
    }
  }
}
