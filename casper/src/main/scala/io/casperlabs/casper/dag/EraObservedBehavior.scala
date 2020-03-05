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

  def latestMessagesInEra(
      keyBlockHash: BlockHash
  )(implicit ev: A =:= Message): Map[Validator, Set[Message]] =
    data.get(keyBlockHash) match {
      case None => Map.empty[Validator, Set[Message]]
      case Some(lms) =>
        lms.mapValues {
          case Empty               => Set.empty[Message]
          case Honest(msg)         => Set(msg)
          case Equivocated(m1, m2) => Set(m1, m2)
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
    * @param stop
    * @return
    */
  def messageJPast[F[_]: MonadThrowable](
      dag: DagLookup[F],
      justifications: List[Message],
      erasObservedBehavior: LocalDagView[Message],
      stop: Message
  ): F[MessageJPast[Message]] = {
    type PerEraValidatorMessages =
      Map[ByteString, Map[Validator, ObservedValidatorBehavior[Message]]]
    import ObservedValidatorBehavior._

    val stream = DagOperations
      .toposortJDagDesc(dag, justifications)
      .takeUntil(_ == stop)
      .filter(!_.isGenesisLike) // Not interested in Genesis block

    val keyBlockHashes = erasObservedBehavior.keyBlockHashes

    val initStates: PerEraValidatorMessages =
      keyBlockHashes.toList
        .map(
          kbh =>
            kbh -> erasObservedBehavior
              .validatorsInEra(kbh)
              .filterNot(_.isEmpty()) // Not interested in Genesis validator.
              .map(_ -> ObservedValidatorBehavior.Empty)
              .toMap
        )
        .toMap

    // Previously seen message from the validator.
    val prevMessageSeen: Map[ByteString, Map[Validator, Message]] = Map.empty
    val validatorStatusMatches
        : (ByteString, Validator, ObservedValidatorBehavior[Message]) => Boolean =
      (era, validator, seenBehavior) => erasObservedBehavior.data(era)(validator) == seenBehavior

    // If we've seen, in the j-past-cone of the message, the same statuses
    // as ones we have collected locally then we're done.
    // This is correct b/c a node has to download all dependencies first,
    // before validating the block.
    val isDone: PerEraValidatorMessages => Boolean = state =>
      state.forall {
        case (era, eraLMS) =>
          eraLMS.forall {
            case (validator, seenStatus) =>
              validatorStatusMatches(era, validator, seenStatus)
          }
      }

    stream
      .foldWhileLeft((initStates, prevMessageSeen)) {
        case ((latestMessages, prevMessages), message) =>
          if (isDone(latestMessages)) {
            Right((latestMessages, prevMessages))
          } else {
            val msgCreator = message.validatorId
            val msgEra     = message.eraId
            val eraMsgs    = latestMessages(msgEra)
            Left(eraMsgs(msgCreator) match {
              case Honest(prevMsg) =>
                if (prevMessages(msgEra)(msgCreator).validatorPrevMessageHash == message.messageHash)
                  (
                    latestMessages,
                    prevMessages
                      .updated(msgEra, prevMessages(msgEra).updated(msgCreator, message))
                  )
                else {
                  // Validator equivocated in this era
                  (
                    latestMessages.updated(
                      msgEra,
                      latestMessages(msgEra).updated(msgCreator, Equivocated(prevMsg, message))
                    ),
                    prevMessages
                  )
                }
              case Empty =>
                (
                  latestMessages
                    .updated(msgEra, latestMessages(msgEra).updated(msgCreator, Honest(message))),
                  prevMessages
                    .updated(
                      msgEra,
                      prevMessages.getOrElse(msgEra, Map.empty).updated(msgCreator, message)
                    )
                )
              case Equivocated(_, _) => (latestMessages, prevMessages)
            })
          }
      }
      .map {
        case (observedBehaviors, _) =>
          new EraObservedBehavior[Message](observedBehaviors).asInstanceOf[MessageJPast[Message]]
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
