package io.casperlabs.casper.highway

import cats._
import cats.implicits._
import cats.effect.{Clock, Sync}
import io.casperlabs.casper.consensus.Era
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.dag.{DagRepresentation, DagStorage, FinalityStorageReader}
import io.casperlabs.storage.era.EraStorage
import io.casperlabs.casper.util.DagOperations, DagOperations.Key

object EraSupervisor {

  // Return type for the initialization of active eras along the era tree.
  type EraWithAgenda[F[_]] = (EraRuntime[F], EraRuntime.Agenda)

  // Helper for traversing the era tree.
  implicit def `Key[EraWithAgenda]`[F[_]] = Key.instance[EraWithAgenda[F], BlockHash] {
    case (runtime, _) => runtime.era.keyBlockHash
  }

  /** Walk up the era tree, starting from the tips, and collect any era that's
    * not finished yet. We know they are not finished because they have something
    * they want to do. If there's nothing, we'll have to rely on incoming messages
    * to establish the missing links for us.
    */
  def collectActiveEras[F[_]: Sync: EraStorage](
      makeRuntime: Era => F[EraRuntime[F]]
  ): F[List[EraWithAgenda[F]]] = {
    // Eras without agendas are finished.
    def selectActives(eras: List[EraWithAgenda[F]]) =
      eras.filterNot(_._2.isEmpty)

    for {
      tips <- EraStorage[F].getChildlessEras

      activeTips <- tips.toList.traverse { era =>
                     for {
                       runtime <- makeRuntime(era)
                       agenda  <- runtime.initAgenda
                     } yield runtime -> agenda
                   } map { selectActives }

      active <- DagOperations
                 .bfTraverseF[F, EraWithAgenda[F]](activeTips) {
                   case (runtime, _) if !runtime.era.parentKeyBlockHash.isEmpty =>
                     for {
                       parentEra     <- EraStorage[F].getEraUnsafe(runtime.era.parentKeyBlockHash)
                       parentRuntime <- makeRuntime(parentEra)
                       parentAgenda  <- parentRuntime.initAgenda
                     } yield selectActives(List(parentRuntime -> parentAgenda))

                   case _ =>
                     List.empty.pure[F]
                 }
                 .toList
    } yield active
  }
}
