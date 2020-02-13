package io.casperlabs.casper.util

import shapeless.tag.@@
import io.casperlabs.storage.dag.DagRepresentation.Validator
import io.casperlabs.storage.BlockHash
import com.google.protobuf.ByteString
import io.casperlabs.models.Message
import io.casperlabs.casper.util.ObservedValidatorBehavior._
import com.github.ghik.silencer.silent

@silent("is never used")
final class EraObservedBehavior[A] private (
    val data: Map[BlockHash, Map[Validator, ObservedValidatorBehavior[A]]]
) {
  // Returns set of equivocating validators that are visible in the j-past-cone
  // of the era.
  def equivocatorsVisibleInEras(
      keyBlockHashes: Set[BlockHash]
  ): Set[Validator] =
    data
      .collect {
        case (keyBlockHash, lms) if keyBlockHashes(keyBlockHash) =>
          lms.filter(_._2.isEquivocated).keySet
      }
      .flatten
      .toSet

  def keyBlockHashes: Set[BlockHash] = data.keySet

  def validatorsInEra(keyBlockHash: BlockHash): Set[Validator] =
    data.get(keyBlockHash).fold(Set.empty[Validator])(_.keySet)

//    def latestMessagesInEra[F[_]: MonadThrowable: DagLookup](
//        keyBlockHash: ByteString
//    )(implicit ev: A =:= ByteString): F[Map[Validator, Set[Message]]] =
//      data.get(keyBlockHash) match {
//        case None => Map.empty[Validator, Set[Message]].pure[F]
//        case Some(lms) =>
//          lms.toList
//            .traverse {
//              case (v, observedBehavior) =>
//                val lm: F[Set[Message]] = observedBehavior match {
//                  case Empty => Set.empty[Message].pure[F]
//                  case Honest(hash) =>
//                    DagLookup[F].lookupUnsafe(hash.asInstanceOf[ByteString]).map(Set(_))
//                  case Equivocated(m1, m2) =>
//                    MonadThrowable[F].map2(
//                      DagLookup[F].lookupUnsafe(m1.asInstanceOf[ByteString]),
//                      DagLookup[F].lookupUnsafe(m2.asInstanceOf[ByteString])
//                    ) { case (a, b) => Set(a, b) }
//
//                }
//                lm.map(v -> _)
//            }
//            .map(_.toMap)
//      }

  def latestMessagesInEra(
      keyBlockHash: BlockHash
  )(implicit ev: A =:= Message): Map[Validator, Set[Message]] =
    data.get(keyBlockHash) match {
      case None => Map.empty[Validator, Set[Message]]
      case Some(lms) =>
        lms.mapValues {
          case Empty       => Set.empty[Message]
          case Honest(msg) => Set(msg.asInstanceOf[Message])
          case Equivocated(m1, m2) =>
            Set(m1.asInstanceOf[Message], m2.asInstanceOf[Message])
        }
    }
}

object EraObservedBehavior {

  sealed trait LocalView
  sealed trait MessageView

  type LocalDagView[A] = EraObservedBehavior[A] @@ LocalView
  type MessageJPast[A] = EraObservedBehavior[A] @@ MessageView

  def local(data: Map[ByteString, Map[Validator, Set[Message]]]): LocalDagView[Message] =
    apply(data).asInstanceOf[LocalDagView[Message]]

  def messageJPast(data: Map[ByteString, Map[Validator, Set[Message]]]): MessageJPast[Message] =
    apply(data).asInstanceOf[MessageJPast[Message]]

  private def apply(
      data: Map[ByteString, Map[Validator, Set[Message]]]
  ): EraObservedBehavior[Message] =
    new EraObservedBehavior(data.mapValues(_.map {
      case (v, lms) =>
        if (lms.isEmpty) v -> ObservedValidatorBehavior.Empty
        else if (lms.size == 1) v -> ObservedValidatorBehavior.Honest(lms.head)
        else v                    -> ObservedValidatorBehavior.Equivocated(lms.head, lms.drop(1).head)
    }))
}
