package io.casperlabs.client

import cats.{Monad, _}
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper._
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.graphz._

package object Constant {
  // For debugging it is usefull to change Invis to Dotted.
  val Invisible = Dotted
}

final case class ValidatorBlock(
    blockHash: String,
    parentsHashes: List[String],
    justifications: List[String]
)

final case class GraphConfig(showJustificationLines: Boolean = false)

object GraphzGenerator {
  type BlockHash        = ByteString
  type Rank             = Long
  type ValidatorsBlocks = Map[Rank, List[ValidatorBlock]]

  final case class DagInfo[G[_]](
      validators: Map[String, ValidatorsBlocks] = Map.empty,
      ranks: List[Rank] = List.empty
  )

  object DagInfo {
    def empty[G[_]]: DagInfo[G] = DagInfo[G]()
  }

  private def hexShort(hash: ByteString) = {
    val str = Base16.encode(hash.toByteArray)
    if (str.length <= 10) str else str.substring(0, 10) + "..."
  }

  def dagAsCluster[
      G[_]: Monad: GraphSerializer
  ](
      // Going from newest to oldest.
      blockInfos: List[BlockInfo],
      config: GraphConfig
  ): G[Graphz[G]] = {
    val acc = toDagInfo[G](blockInfos)

    val lastFinalizedBlockHash =
      blockInfos
        .find(_.getStatus.faultTolerance > 0)
        .map(x => hexShort(x.getSummary.blockHash))
        .getOrElse("")

    val ranks          = acc.ranks.reverse
    val firstRank      = ranks.head
    val validators     = acc.validators
    val validatorsList = validators.toList.sortBy(_._1)

    for {
      g <- initGraph[G]("dag")
      // block hashes of parents of the very first rank
      allAncestors = validatorsList
        .map(_._2.getOrElse(firstRank, List.empty[ValidatorBlock]))
        .flatten
        .map(_.parentsHashes)
        .flatten
        .distinct
        .sorted
      // draw ancestors first
      _ <- allAncestors.traverse(
            ancestor =>
              g.node(
                ancestor,
                style = styleFor(ancestor, lastFinalizedBlockHash),
                shape = Box
              )
          )
      // create invisible edges from ancestors to first node in each cluster for proper alignment
      _ <- validatorsList
            .map {
              case (id, blocks) =>
                allAncestors.traverse(
                  ancestor =>
                    nodesForRank(id, firstRank, blocks, lastFinalizedBlockHash)
                      .map(node => (node, ancestor))
                )
            }
            .flatten
            .flatten
            .traverse {
              case (node, ancestor) =>
                g.edge(ancestor, node._2, style = Some(Constant.Invisible))
            }

      // draw clusters per validator
      _ <- validatorsList.traverse {
            case (id, blocks) =>
              g.subgraph(
                validatorCluster(id, blocks, ranks, lastFinalizedBlockHash)
              )
          }
      // draw parent dependencies
      _ <- drawParentDependencies[G](g, validatorsList.map(_._2))
      // draw justification dotted lines
      _ <- config.showJustificationLines.fold(
            drawJustificationDottedLines[G](g, validators),
            ().pure[G]
          )
      _ <- g.close
    } yield g
  }

  private def toDagInfo[G[_]](
      blockInfos: List[BlockInfo]
  ): DagInfo[G] = {
    val ranks = blockInfos.map(_.getSummary.rank).distinct

    val validators = blockInfos
      .map(_.getSummary)
      .filter(_.validatorPublicKey.size > 0)
      .foldMap { b =>
        val blockHash       = hexShort(b.blockHash)
        val blockSenderHash = hexShort(b.validatorPublicKey)
        val parents         = b.parentHashes.toList.map(hexShort)
        val justifications = b.justifications
          .map(_.latestBlockHash)
          .map(hexShort)
          .toSet
          .toList

        val validatorBlocks =
          Map(b.rank -> List(ValidatorBlock(blockHash, parents, justifications)))

        Map(blockSenderHash -> validatorBlocks)
      }

    DagInfo[G](validators, ranks)
  }

  private def initGraph[G[_]: Monad: GraphSerializer](name: String): G[Graphz[G]] =
    Graphz[G](
      name,
      DiGraph,
      rankdir = Some(BT),
      splines = Some("false"),
      node = Map("width" -> "0", "height" -> "0", "margin" -> "0.03", "fontsize" -> "8")
    )

  private def drawParentDependencies[G[_]: Applicative](
      g: Graphz[G],
      validators: List[ValidatorsBlocks]
  ): G[Unit] =
    validators
      .flatMap(_.values.toList)
      .flatten
      .traverse {
        case ValidatorBlock(blockHash, parentsHashes, _) =>
          parentsHashes.zipWithIndex
            .traverse {
              case (p, index) =>
                // Bolding the edge to main parent
                val style = if (index == 0) {
                  Some(Bold)
                } else {
                  None
                }
                g.edge(blockHash, p, style = style, constraint = Some(false))
            }
      }
      .as(())

  private def drawJustificationDottedLines[G[_]: Applicative](
      g: Graphz[G],
      validators: Map[String, ValidatorsBlocks]
  ): G[Unit] =
    validators.values.toList
      .flatMap(_.values.toList)
      .flatten
      .traverse {
        case ValidatorBlock(blockHash, _, justifications) =>
          justifications
            .traverse(
              j =>
                g.edge(
                  blockHash,
                  j,
                  style = Some(Dotted),
                  constraint = Some(false),
                  arrowHead = Some(NoneArrow)
                )
            )

      }
      .as(())

  private def nodesForRank(
      validatorId: String,
      rank: Rank,
      blocks: ValidatorsBlocks,
      lastFinalizedBlockHash: String
  ): List[(Option[GraphStyle], String)] = {
    val blocksForRank = blocks.getOrElse(rank, List.empty)
    blocksForRank.size match {
      case 0 => List((Some(Constant.Invisible), s"${rank.show}_$validatorId"))
      case _ =>
        blocksForRank
          .map(b => (styleFor(b.blockHash, lastFinalizedBlockHash), b.blockHash))
    }
  }

  private def validatorCluster[G[_]: Monad: GraphSerializer](
      id: String,
      blocks: ValidatorsBlocks,
      ranks: List[Rank],
      lastFinalizedBlockHash: String
  ): G[Graphz[G]] =
    for {
      g     <- Graphz.subgraph[G](s"cluster_$id", DiGraph, label = Some(id))
      nodes = ranks.map(rank => nodesForRank(id, rank, blocks, lastFinalizedBlockHash)).flatten
      _ <- nodes.traverse {
            case (style, name) => g.node(name, style = style, shape = Box)
          }

      _ <- ranks.reverse.tail.reverse
            .map(
              rank =>
                nodesForRank(id, rank, blocks, lastFinalizedBlockHash).map(n1 => {
                  nodesForRank(id, rank + 1, blocks, lastFinalizedBlockHash).map(n2 => {
                    (n1, n2)
                  })
                })
            )
            .flatten
            .flatten
            .traverse {
              case ((_, n1), (_, n2)) => g.edge(n1, n2, style = Some(Constant.Invisible))
            }

      _ <- g.close
    } yield g

  private def styleFor(blockHash: String, lastFinalizedBlockHash: String): Option[GraphStyle] =
    if (blockHash == lastFinalizedBlockHash) Some(Filled) else None

}
