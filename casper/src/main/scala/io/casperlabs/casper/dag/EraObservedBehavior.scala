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
import scala.util.control.NoStackTrace

final class EraObservedBehavior[A] private (
    val data: Map[BlockHash, Map[Validator, ObservedValidatorBehavior[A]]]
) {
  private lazy val equivocators: Map[ByteString, Set[Validator]] =
    data
      .collect {
        case (keyBlockHash, lms) if keyBlockHashes(keyBlockHash) =>
          keyBlockHash -> lms.filter(_._2.isEquivocated).keySet
      }
  val erasValidators: Map[BlockHash, Set[Validator]] = data.mapValues(_.keySet)

  // Returns set of equivocating validators that are visible in the j-past-cone
  // of the era.
  def equivocatorsVisibleInEras(
      keyBlockHashes: Set[BlockHash]
  ): Set[Validator] =
    keyBlockHashes.foldLeft(Set.empty[Validator])(_ ++ equivocators(_))

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

  type LocalDagView[A] = EraObservedBehavior[A] @@ LocalView
  type MessageJPast[A] = EraObservedBehavior[A] @@ MessageView

  def local(data: Map[ByteString, Map[Validator, Set[Message]]]): LocalDagView[Message] =
    apply(data).asInstanceOf[LocalDagView[Message]]

  // An enum that describes validator's observed state.
  // When we traverse the j-past-cone of a message we will start from the tips,
  // following justificiations and learning what was the creator's view of the world.
  // We need to discover what's the status but at the end return where we started at.
  private sealed trait ValidatorStatus {
    /* Update validator's state while traversing his swimlane by adding new message.
     */
    def validate(m: Message): ValidatorStatus = this match {
      case Undefined           => Swimlane(SwimlaneTip(m), PrevSeen(m))
      case Swimlane(tip, prev) =>
        // Example:
        // a1 <- a2 <- a3
        //     \ a2Prime
        // We start at a3 (the tip of the swimlane) and traverse backwards.
        if (prev.validatorPrevMessageHash == m.messageHash)
          // Honest validator that builds proper chain.
          // In the above example our previous message would be `a3` and current one `a2`.
          Swimlane(tip, PrevSeen(m))
        else
          // Newer message didn't cite the previous one.
          // We have the first fork in the swimlane.
          // In the example above, we would see a2Prime now and learn that previous
          // message (a3) did not point at it with ``validatorPrevMessageHash`,
          // This is the equivocation.
          Equivocation(
            Set(
              Swimlane(tip, prev),
              Swimlane(SwimlaneTip(m), PrevSeen(m))
            )
          )
      case Equivocation(tips) =>
        // We already know that the validator is equivocator but we're still travering his messages.
        Equivocation(
          tips
            .find {
              case Swimlane(_, lastSeen) =>
                lastSeen.validatorPrevMessageHash == m.messageHash
            }
            .fold(
              // New fork in the swmilane. Example:
              // a1 <- a2 <- a3
              //  \ \-a2Prime
              //   \-a1Prime
              tips + Swimlane(SwimlaneTip(m), PrevSeen(m))
            ) {
              case old @ Swimlane(tip, _) =>
                // Example:
                // a1 <- a2 <- a3
                //  \ \-a2Prime
                //   \-a1Prime - a1PrimePrime
                // Even though we know `a1PrimePrime` is already an equivocation it properly
                // points at `a1Prime` with its `validatorPrevMessageHash`.
                // If we wanted to return all validator's tips we need to update the state.
                // Replace `lastSeen` because it points at `m` as previous message.
                tips - old + Swimlane(tip, PrevSeen(m))
            }
        )
    }
  }

  private final case class SwimlaneTip(m: Message)
  private final case class PrevSeen(m: Message) {
    def validatorPrevMessageHash: BlockHash = m.validatorPrevMessageHash
  }

  // Hasn't seen any messages yet.
  private case object Undefined extends ValidatorStatus
  // `Tip` is the latest message by that validator observed in the j-past-cone.
  // `prev` is the previous message we saw in the swimlane.
  private case class Swimlane(tip: SwimlaneTip, prev: PrevSeen) extends ValidatorStatus
  // We still need to store the tip of the swimlane b/c this will be part of justifications.
  private case class Equivocation(tips: Set[Swimlane]) extends ValidatorStatus

  private def unknown: ValidatorStatus = Undefined

  /**
    * Calculates panorama of a set of justifications.
    *
    * Panorama is a "view" into the past of the message (j-past-cone).
    * It is the latest message (or multiple messages) per validator seen
    * in the j-past-cone of the message.
    *
    * We start with the `eraObservedBehavior` which is a local view of the DAG.
    * It contains superset of what the `justificaions` may point at.
    *
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

    // Map a message's direct justifications to a map that represents its j-past-cone view.
    val toEraMap: List[Message] => Map[EraId, Map[Validator, Set[Message]]] =
      _.groupBy(_.eraId).mapValues(_.groupBy(_.validatorId).mapValues(_.toSet))

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
        .filterNot(_._1.isEmpty) // filter out Genesis
        .mapValues(_.toList.map(_ -> Set.empty[Message]).toMap)

    val observedKeyBlocks =
      erasObservedBehavior.keyBlockHashes.map(PrettyPrinter.buildString(_)).mkString("[", ", ", "]")

    (baseMap |+| toEraMap(justifications.filterNot(_.isGenesisLike))).toList
      .traverse {
        case (era, validatorsLatestMessages) =>
          validatorsLatestMessages.toList
            .traverse {
              case (validator, messages) =>
                erasObservedBehavior.getStatus(era, validator) match {
                  case None =>
                    val msg = s"Message directly cites validator " +
                      s"${PrettyPrinter.buildString(validator)} in an era ${PrettyPrinter
                        .buildString(era)} " +
                      s"but expected messages only from $observedKeyBlocks eras."
                    MonadThrowable[F].raiseError[(Validator, Set[Message])](
                      new IllegalStateException(msg) with NoStackTrace
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
                    if (messages.size > 1)
                      // `messages` should be the latest messages by that validator in this era.
                      // They are tips of his swimlane and evidences for equivocation.
                      equivocated(validator, messages).pure[F]
                    else {
                      val startingPoints =
                        validatorsLatestMessages.values.flatten.toList

                      // Latest message by that validator in this era visible in the j-past-cone
                      // of the message received.
                      val jConeTips = DagOperations
                        .swimlaneVFromJustifications[F](
                          validator,
                          startingPoints,
                          dag
                        )
                        .takeWhile(_.eraId == era)
                        .foldWhileLeft(unknown) {
                          case (acc, msg) =>
                            acc.validate(msg) match {
                              case Undefined       => unknown.asLeft[ValidatorStatus]
                              case h: Swimlane     => h.asLeft[ValidatorStatus]
                              case e: Equivocation => e.asRight[ValidatorStatus]
                            }
                        }

                      jConeTips.map {
                        case Undefined =>
                          // No message in the j-past-cone
                          empty(validator)
                        case Swimlane(tip, _) =>
                          // Validator is honest in the j-past-cone
                          honest(validator, tip.m)
                        case Equivocation(tips) =>
                          equivocated(validator, tips.map(_.tip.m))
                      }
                    }
                }
            }
            .map(era -> _.toMap)
      }
      .map(_.toMap)
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

  sealed trait LocalView

  sealed trait MessageView
}
