package io.casperlabs.client

import cats.{Monad, _}
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper._
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.graphz._

final case class ValidatorBlock(
    blockHash: String,
    parentsHashes: List[String],
    justifications: List[String]
)

object ValidatorBlock {
  implicit val validatorBlockMonoid: Monoid[ValidatorBlock] = new Monoid[ValidatorBlock] {
    def empty: ValidatorBlock = ValidatorBlock("", List.empty[String], List.empty[String])
    def combine(vb1: ValidatorBlock, vb2: ValidatorBlock): ValidatorBlock =
      ValidatorBlock(
        vb2.blockHash,
        vb1.parentsHashes ++ vb2.parentsHashes,
        vb1.justifications ++ vb2.justifications
      )
  }
}

final case class GraphConfig(showJustificationLines: Boolean = false)

object GraphzGenerator {
  type BlockHash        = ByteString
  type ValidatorsBlocks = Map[Long, ValidatorBlock]

  final case class DagInfo[G[_]](
      validators: Map[String, ValidatorsBlocks] = Map.empty,
      timeseries: List[Long] = List.empty
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

    val timeseries     = acc.timeseries.reverse
    val firstTs        = timeseries.head
    val validators     = acc.validators
    val validatorsList = validators.toList.sortBy(_._1)

    for {
      g <- initGraph[G]("dag")
      // block hashes of parents of the very first rank
      allAncestors = validatorsList
        .flatMap {
          case (_, blocks) =>
            blocks.get(firstTs).map(_.parentsHashes).getOrElse(List.empty[String])
        }
        .distinct
        .sorted
      // draw ancesotrs first
      _ <- allAncestors.traverse(
            ancestor =>
              g.node(
                ancestor,
                style = styleFor(ancestor, lastFinalizedBlockHash),
                shape = Box
              )
          )
      // create invisible edges from ancestors to first node in each cluster for proper alligment
      _ <- validatorsList.traverse {
            case (id, blocks) =>
              allAncestors.traverse(ancestor => {
                val node = nodeForTs(id, firstTs, blocks, lastFinalizedBlockHash)._2
                g.edge(ancestor, node, style = Some(Invis))
              })
          }
      // draw clusters per validator
      _ <- validatorsList.traverse {
            case (id, blocks) =>
              g.subgraph(
                validatorCluster(id, blocks, timeseries, lastFinalizedBlockHash)
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
    val timeseries = blockInfos.map(_.getSummary.getHeader.rank).distinct

    val validators = blockInfos.map(_.getSummary).foldMap { b =>
      val timeEntry       = b.getHeader.rank
      val blockHash       = hexShort(b.blockHash)
      val blockSenderHash = hexShort(b.getHeader.validatorPublicKey)
      val parents         = b.getHeader.parentHashes.toList.map(hexShort)
      val justifications = b.getHeader.justifications
        .map(_.latestBlockHash)
        .map(hexShort)
        .toSet
        .toList

      val validatorBlocks =
        Map(timeEntry -> ValidatorBlock(blockHash, parents, justifications))

      Map(blockSenderHash -> validatorBlocks)
    }

    DagInfo[G](validators, timeseries)
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
      .traverse {
        case ValidatorBlock(blockHash, parentsHashes, _) =>
          parentsHashes.zipWithIndex
            .traverse {
              case (p, index) =>
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

  private def nodeForTs(
      validatorId: String,
      ts: Long,
      blocks: ValidatorsBlocks,
      lastFinalizedBlockHash: String
  ): (Option[GraphStyle], String) =
    blocks.get(ts) match {
      case Some(ValidatorBlock(blockHash, _, _)) =>
        (styleFor(blockHash, lastFinalizedBlockHash), blockHash)
      case None => (Some(Invis), s"${ts.show}_$validatorId")
    }

  private def validatorCluster[G[_]: Monad: GraphSerializer](
      id: String,
      blocks: ValidatorsBlocks,
      timeseries: List[Long],
      lastFinalizedBlockHash: String
  ): G[Graphz[G]] =
    for {
      g     <- Graphz.subgraph[G](s"cluster_$id", DiGraph, label = Some(id))
      nodes = timeseries.map(ts => nodeForTs(id, ts, blocks, lastFinalizedBlockHash))
      _ <- nodes.traverse {
            case (style, name) => g.node(name, style = style, shape = Box)
          }
      _ <- nodes.zip(nodes.drop(1)).traverse {
            case ((_, n1), (_, n2)) => g.edge(n1, n2, style = Some(Invis))
          }
      _ <- g.close
    } yield g

  private def styleFor(blockHash: String, lastFinalizedBlockHash: String): Option[GraphStyle] =
    if (blockHash == lastFinalizedBlockHash) Some(Filled) else None

}
