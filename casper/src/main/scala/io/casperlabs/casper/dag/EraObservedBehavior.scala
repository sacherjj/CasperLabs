package io.casperlabs.casper.dag

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.dag.ObservedValidatorBehavior.{Empty, Equivocated, Honest}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.models.Message
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.dag.DagLookup
import io.casperlabs.storage.dag.DagRepresentation.Validator
import shapeless.tag.@@
import io.casperlabs.casper.PrettyPrinter
import io.casperlabs.models.Message.JRank

final class EraObservedBehavior[A] private (
    val data: Map[BlockHash, Map[Validator, ObservedValidatorBehavior[A]]]
) {
  // Returns set of equivocating validators that are visible in the j-past-cone
  // of the era.
  def equivocatorsVisibleInEras(
      keyBlockHashes: Set[BlockHash]
  ): Set[Validator] =
    keyBlockHashes.foldLeft(Set.empty[Validator])(_ ++ equivocators(_))

  private lazy val equivocators: Map[ByteString, Set[Validator]] =
    data
      .collect {
        case (keyBlockHash, lms) if keyBlockHashes(keyBlockHash) =>
          keyBlockHash -> lms.filter(_._2.isEquivocated).keySet
      }

  val erasValidators: Map[BlockHash, Set[Validator]] = data.mapValues(_.keySet)

  def keyBlockHashes: Set[BlockHash] = data.keySet

  def validatorsInEra(keyBlockHash: BlockHash): Set[Validator] =
    data.get(keyBlockHash).fold(Set.empty[Validator])(_.keySet)

  def latestMessagesInEra(
      keyBlockHash: BlockHash
  ): Map[Validator, Set[A]] =
    data.get(keyBlockHash) match {
      case None => Map.empty[Validator, Set[A]]
      case Some(lms) =>
        lms.mapValues {
          case Empty               => Set.empty[A]
          case Honest(msg)         => Set(msg)
          case Equivocated(m1, m2) => Set(m1, m2)
        }
    }

  def getStatus(
      keyBlockHash: BlockHash,
      validator: Validator
  ): Option[ObservedValidatorBehavior[A]] =
    data.get(keyBlockHash).flatMap(_.get(validator))
}

object EraObservedBehavior {

  sealed trait LocalView
  sealed trait MessageView

  type LocalDagView[A] = EraObservedBehavior[A] @@ LocalView
  type MessageJPast[A] = EraObservedBehavior[A] @@ MessageView

  def local(data: Map[ByteString, Map[Validator, Set[Message]]]): LocalDagView[Message] =
    apply(data).asInstanceOf[LocalDagView[Message]]

  /**
    * Calculates panorama of a set of justifications.
    *
    * Panorama is a "view" into the past of the message (j-past-cone).
    * It is the latest message (or multiple messages) per validator seen
    * in the j-past-cone of the message.
    *
    * This particular method is capped by a "stop block". It won't consider
    * blocks created before that stop block.
    *
    * NOTE: In the future, this will be using Andreas' Merkle trie optimization.
    * @param dag
    * @param justifications
    * @param erasObservedBehavior
    * @return
    */
  def messageJPast[F[_]: MonadThrowable](
      dag: DagLookup[F],
      justifications: List[Message],
      erasObservedBehavior: LocalDagView[Message]
  ): F[MessageJPast[Message]] = {

    type EraId = ByteString

    import ObservedValidatorBehavior._

    val toEraMap: Message => F[Map[EraId, Map[Validator, Set[Message]]]] =
      m =>
        m.justifications.toList
          .traverse(j => dag.lookupUnsafe(j.latestBlockHash))
          .map { messages =>
            messages
              .groupBy(_.eraId)
              .mapValues(_.groupBy(_.validatorId).mapValues(_.toSet)) |+| Map(
              m.eraId -> Map(m.validatorId -> Set(m))
            )
          }

    def empty(v: Validator): (Validator, Set[Message]) =
      (v, Set.empty[Message])
    def honest(v: Validator, m: Message): (Validator, Set[Message]) =
      (v, Set(m))
    def equivocated(v: Validator, msgs: Set[Message]): (Validator, Set[Message]) =
      (v, msgs)

    // A map from every observed era to a set of validators that produced a message in each of the eras.
    // We will use it later when merging two p-cones and not skip validators not visible in the direct justifications.
    val baseMap =
      erasObservedBehavior.erasValidators
        .filterNot(_.isEmpty) // filter out Genesis
        .mapValues(_.toList.map(_ -> Set.empty[Message]).toMap)

    def mergeJPastCones(
        a: Map[EraId, Map[Validator, Set[Message]]],
        b: Map[EraId, Map[Validator, Set[Message]]]
    ): F[Map[EraId, Map[Validator, Set[Message]]]] =
      for {
        merged <- (a |+| b).toList
                   .traverse {
                     case (era, validatorsLatestMessages) =>
                       validatorsLatestMessages.toList
                         .traverse {
                           case (validator, messages) =>
                             erasObservedBehavior.getStatus(era, validator) match {
                               case None =>
                                 val msg = s"Error when traversing j-past-cone. Expected to have seen a message from validator " +
                                   s"${PrettyPrinter.buildString(validator)} in era ${PrettyPrinter.buildString(era)} but it was not present."
                                 MonadThrowable[F].raiseError[(Validator, Set[Message])](
                                   new IllegalStateException(msg)
                                 )
                               case Some(Empty) =>
                                 // We haven't seen any messages from that validator in this era.
                                 // There can't be any in the justifications either.
                                 empty(validator).pure[F]
                               case Some(Honest(_)) =>
                                 if (messages.nonEmpty) {
                                   import io.casperlabs.shared.Sorting.jRankOrdering
                                   // Since we know that validator is honest we can pick the newest message.
                                   honest(validator, messages.maxBy(_.jRank)).pure[F]
                                 } else {
                                   // There are no messages in the direct justifications
                                   // but we know that local DAG has seen messages from that validator.
                                   // We have to look for them in the indirect ones but it still might not be there
                                   // (because creator of the messages hasn't seen anything from that validator).
                                   DagOperations
                                     .swimlaneVFromJustifications[F](
                                       validator,
                                       validatorsLatestMessages.values.flatten.toList,
                                       dag
                                     )
                                     .takeWhile(_.eraId == era)
                                     .headOption
                                     .map(_.fold(empty(validator))(honest(validator, _)))
                                 }
                               case Some(Equivocated(_, _)) =>
                                 // Local DAG has detected `validator` as an equivocator.
                                 // We have to find out what's the status of that validator in the j-past-cone.
                                 if (messages.size == 1)
                                   // Earlier we validated that `validator_prev_msg` is set correctly
                                   // for each of the messages separately.
                                   honest(validator, messages.head).pure[F]
                                 else {
                                   val startingPoints =
                                     if (messages.isEmpty)
                                       // There's no messages by that validator in an era.
                                       // Use messages from other validators as starting points.
                                       validatorsLatestMessages.values.flatten.toList
                                     else
                                       // Use latest messages by that validator in an era.
                                       messages.toList

                                   DagOperations
                                     .swimlaneVFromJustifications[F](
                                       validator,
                                       startingPoints,
                                       dag
                                     )
                                     .takeWhile(_.eraId == era)
                                     .foldWhileLeft(Map.empty[JRank, Set[Message]]) {
                                       case (acc, msg) =>
                                         if (acc.contains(msg.jRank))
                                           // Stop folding when we detect equivocating pair.
                                           Right(Map(msg.jRank -> (acc(msg.jRank) + msg)))
                                         else Left(acc.updated(msg.jRank, Set(msg)))
                                     }
                                     .map { validatorsEraMessages =>
                                       if (validatorsEraMessages.isEmpty)
                                         empty(validator)
                                       else {
                                         validatorsEraMessages
                                           .find(_._2.size > 1)
                                           .fold {
                                             import io.casperlabs.shared.Sorting.jRankOrdering
                                             // There's no jRank at which there's more than 1 message.
                                             // Pick the newest one.
                                             val msg = validatorsEraMessages.maxBy(_._1)._2.head
                                             honest(validator, msg)
                                           } {
                                             case (_, evidences) =>
                                               // `evidences` is a set of messages that have the same jRank.
                                               equivocated(validator, evidences)
                                           }
                                       }
                                     }
                                 }
                             }
                         }
                         .map(era -> _.toMap)
                   }
                   .map(_.toMap)
      } yield merged

    justifications
      .map(toEraMap)
      .foldLeftM(baseMap) { case (acc, eraMapF) => eraMapF.flatMap(mergeJPastCones(acc, _)) }
      .map { tips =>
        val nonEmptyTips = tips.mapValues(_.filterNot(_._2.isEmpty))
        apply(nonEmptyTips).asInstanceOf[MessageJPast[Message]]
      }
  }

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
