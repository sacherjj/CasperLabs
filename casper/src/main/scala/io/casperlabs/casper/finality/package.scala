package io.casperlabs.casper

import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.syntax.foldable._
import cats.instances.list._
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.dag.DagOperations
import io.casperlabs.models.Message
import io.casperlabs.storage.dag.{AncestorsStorage, DagRepresentation}

package object finality {
  type Level = Long

  /**
    * Returns a child of previous LFB that the `message` votes for
    * (is descendant along the main-tree path).
    *
    * Returns `None` if `message` is not descendant of previous LFB.
    */
  def votedBranch[F[_]: MonadThrowable: AncestorsStorage](
      dag: DagRepresentation[F],
      lfbHash: BlockHash,
      message: Message
  ): F[Option[Message]] =
    dag
      .getMainChildren(lfbHash)
      .flatMap(_.toList.traverse(dag.lookupUnsafe(_)))
      .map(_.filter(_.isBlock))
      .flatMap { lfbChildren =>
        lfbChildren.findM(
          c =>
            DagOperations
              .relation[F](message, c)
              .map(_.exists(r => r.isDescendant || r.isEqual))
        )
      }
}
