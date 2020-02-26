package io.casperlabs.node.api

import cats._
import cats.implicits._
import cats.effect._
import com.google.protobuf.ByteString
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.casper.DeployHash
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.consensus.info.{BlockInfo, DeployInfo, Event}
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.casper.EventEmitter
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.api.casper.StreamEventsRequest
import io.casperlabs.node.api.graphql.FinalizedBlocksStream
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.storage.deploy.DeployStorage
import monix.execution.{Ack, Scheduler}
import monix.reactive.{Observable, OverflowStrategy}
import monix.reactive.subjects.ConcurrentSubject
import simulacrum.typeclass
import io.casperlabs.shared.Log

@typeclass trait EventStream[F[_]] extends EventEmitter[F] {
  def subscribe(request: StreamEventsRequest): Observable[Event]
}

object EventStream {
  def create[F[_]: Concurrent: DeployStorage: BlockStorage: DagStorage: Log: Metrics](
      scheduler: Scheduler,
      eventStreamBufferSize: Int
  ): EventStream[F] = {
    // Don't send code in the deploy bodies.
    implicit val deployView = DeployInfo.View.BASIC

    val source =
      ConcurrentSubject.publish[Event](OverflowStrategy.DropOld(eventStreamBufferSize))(scheduler)

    def emit(event: Event) =
      Sync[F].delay(source.onNext(event)).void

    new EventStream[F] {
      import Event._

      override def subscribe(request: StreamEventsRequest): Observable[Event] = {
        import Event.Value._

        val accountFilter: ByteString => Boolean =
          request.getDeployFilter.accountPublicKeys.toSet match {
            case keys if keys.nonEmpty => keys.contains
            case _                     => _ => true
          }

        val deployHashFilter: DeployHash => Boolean =
          request.getDeployFilter.deployHashes.toSet match {
            case hashes if hashes.nonEmpty => hashes.contains
            case _                         => _ => true
          }

        def deployFilter(d: Deploy) =
          accountFilter(d.getHeader.accountPublicKey) && deployHashFilter(d.deployHash)

        source.filter {
          _.value match {
            case Empty => false
            case Value.BlockAdded(_) =>
              request.blockAdded

            case Value.NewFinalizedBlock(_) =>
              request.blockFinalized

            case Value.DeployAdded(e) =>
              request.deployAdded && deployFilter(e.getDeploy)

            case Value.DeployDiscarded(e) =>
              request.deployDiscarded && deployFilter(e.getDeploy)

            case Value.DeployRequeued(e) =>
              request.deployRequeued && deployFilter(e.getDeploy)

            case Value.DeployProcessed(e) =>
              request.deployProcessed && deployFilter(e.getProcessedDeploy.getDeploy)

            case Value.DeployFinalized(e) =>
              request.deployFinalized && deployFilter(e.getProcessedDeploy.getDeploy)

            case Value.DeployOrphaned(e) =>
              request.deployOrphaned && deployFilter(e.getDeployInfo.getDeploy)
          }
        }
      }

      override def blockAdded(blockHash: BlockHash): F[Unit] = {
        BlockAPI.getBlockInfo[F](
          Base16.encode(blockHash.toByteArray),
          BlockInfo.View.FULL
        ) flatMap { blockInfo =>
          emit {
            Event().withBlockAdded(BlockAdded().withBlock(blockInfo))
          }
        }
      } >> {
        DeployStorage[F].reader
          .getProcessedDeploys(blockHash)
          .flatMap { deploys =>
            deploys.traverse { d =>
              emit {
                Event().withDeployProcessed(
                  DeployProcessed().withBlockHash(blockHash).withProcessedDeploy(d)
                )
              }
            }
          }
          .void
      }

      override def newLastFinalizedBlock(
          lfb: BlockHash,
          indirectlyFinalized: Set[BlockHash]
      ): F[Unit] =
        emit {
          Event().withNewFinalizedBlock(
            NewFinalizedBlock(lfb, indirectlyFinalized.toSeq)
          )
        } >> {
          (lfb +: indirectlyFinalized.toList).traverse { blockHash =>
            DeployStorage[F].reader
              .getProcessedDeploys(blockHash)
              .flatMap { deploys =>
                deploys.traverse { d =>
                  emit {
                    Event().withDeployFinalized(
                      DeployFinalized().withBlockHash(blockHash).withProcessedDeploy(d)
                    )
                  }
                }
              }
          }.void
        }

      override def deployAdded(deploy: Deploy): F[Unit] =
        emit {
          Event().withDeployAdded(DeployAdded().withDeploy(deploy.clearBody))
        }

      override def deploysDiscarded(deployHashesWithReasons: Seq[(DeployHash, String)]): F[Unit] = {
        val reasons = deployHashesWithReasons.toMap
        DeployStorage[F].reader
          .getByHashes(reasons.keySet)
          .evalMap { deploy =>
            emit {
              Event().withDeployDiscarded(
                DeployDiscarded().withDeploy(deploy).withMessage(reasons(deploy.deployHash))
              )
            }
          }
          .compile
          .drain
      }

      override def deploysRequeued(deployHashes: Seq[DeployHash]): F[Unit] =
        DeployStorage[F].reader
          .getByHashes(deployHashes.toSet)
          .evalMap { deploy =>
            emit {
              Event().withDeployRequeued(DeployRequeued().withDeploy(deploy))
            }
          }
          .compile
          .drain
    }
  }
}
