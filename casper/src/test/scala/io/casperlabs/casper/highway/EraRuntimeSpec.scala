package io.casperlabs.casper.highway

import cats.{Applicative, Id}
import cats.syntax.option._
import cats.syntax.applicative._
import cats.effect.{Clock, Sync}
import cats.effect.concurrent.Ref
import com.google.protobuf.ByteString
import java.util.concurrent.TimeUnit
import io.casperlabs.casper.consensus.{Block, BlockSummary, Bond, Era}
import io.casperlabs.casper.consensus.state
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS}
import io.casperlabs.models.Message
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.storage.BlockHash
import org.scalatest._
import org.scalactic.source
import scala.concurrent.duration._

class EraRuntimeSpec extends WordSpec with Matchers with Inspectors with TickUtils {
  import EraRuntimeSpec._
  import HighwayConf._
  import EraRuntime.Agenda
  import io.casperlabs.catscontrib.effect.implicits.syncId

  // Let's say we are right at the beginning of the era by default.
  implicit def defaultClock: Clock[Id] = TestClock.frozen[Id](date(2019, 12, 9))

  val conf = HighwayConf(
    tickUnit = TimeUnit.MILLISECONDS,
    genesisEraStart = date(2019, 12, 9),
    eraDuration = EraDuration.FixedLength(days(7)),
    bookingDuration = days(10),
    entropyDuration = hours(3),
    postEraVotingDuration = VotingDuration.FixedLength(days(2)),
    omegaMessageTimeStart = 0.5,
    omegaMessageTimeEnd = 0.75
  )

  val genesis = BlockSummary()
    .withBlockHash(ByteString.copyFromUtf8("genesis"))
    .withHeader(
      Block
        .Header()
        .withState(
          Block
            .GlobalState()
            .withBonds(
              List(
                Bond(validatorKey("Alice")).withStake(state.BigInt("3000")),
                Bond(validatorKey("Bob")).withStake(state.BigInt("4000")),
                Bond(validatorKey("Charlie")).withStake(state.BigInt("5000"))
              )
            )
        )
    )

  def genesisEraRuntime(
      validator: Option[String] = none,
      roundExponent: Int = 0,
      leaderSequencer: LeaderSequencer = LeaderSequencer,
      isSyncedRef: Ref[Id, Boolean] = Ref.of[Id, Boolean](true)
  )(
      implicit C: Clock[Id]
  ) =
    EraRuntime.fromGenesis[Id](
      conf,
      genesis,
      validator.map(mockMessageProducer[Id](_)),
      roundExponent,
      isSyncedRef.get,
      leaderSequencer
    )(Sync[Id], C)

  def assertEvent(
      events: Vector[HighwayEvent]
  )(test: PartialFunction[HighwayEvent, Unit])(implicit pos: source.Position) =
    events.filter(test.isDefinedAt(_)) match {
      case Vector(event) => test(event)
      case Vector()      => fail(s"Could not find matching event in $events")
      case _             => fail(s"Multiple matching events found in $events")
    }

  def assertAgenda(
      agenda: Agenda
  )(test: PartialFunction[Agenda.DelayedAction, Unit])(implicit pos: source.Position) =
    agenda.filter(test.isDefinedAt(_)) match {
      case Vector(action) => test(action)
      case Vector()       => fail(s"Could not find matching action in $agenda")
      case _              => fail(s"Multiple matching actions found in $agenda")
    }

  "EraRuntime" when {
    "started with the genesis block" should {
      val runtime = genesisEraRuntime()

      "use the genesis ticks for the era" in {
        conf.toInstant(Ticks(runtime.era.startTick)) shouldBe conf.genesisEraStart
        conf.toInstant(Ticks(runtime.era.endTick)) shouldBe conf.genesisEraEnd
      }

      "use the genesis block as key and booking block" in {
        runtime.era.keyBlockHash shouldBe genesis.blockHash
        runtime.era.bookingBlockHash shouldBe genesis.blockHash
        runtime.era.leaderSeed shouldBe ByteString.EMPTY
      }

      "not assign a parent era" in {
        runtime.era.parentKeyBlockHash shouldBe ByteString.EMPTY
      }

      "recognize booking block boundaries" in {
        def check(parentDay: Int, blockDay: Int) =
          runtime.isBookingBoundary(
            date(2019, 12, parentDay),
            date(2019, 12, blockDay)
          )
        runtime.bookingBoundaries should contain theSameElementsInOrderAs List(
          date(2019, 12, 13),
          date(2019, 12, 20)
        )
        check(4, 7) shouldBe false   // before the era
        check(11, 13) shouldBe true  // block falls on the first 10 day boundary
        check(13, 13) shouldBe false // parent and child on the same exact round, but parent was first
        check(13, 14) shouldBe false // parent block falls on the first 10 day boundary but the child does not
        check(19, 20) shouldBe true  // block falls on the second 10 day boundary
        check(25, 28) shouldBe false // after the era
      }

      "recognize key block boundaries" in {
        def check(parentDay: Int, blockDay: Int) =
          runtime.isKeyBoundary(
            date(2019, 12, parentDay),
            date(2019, 12, blockDay)
          )
        runtime.keyBoundaries should contain theSameElementsInOrderAs List(
          date(2019, 12, 13) plus hours(3),
          date(2019, 12, 20) plus hours(3)
        )
        check(11, 13) shouldBe false
        check(13, 14) shouldBe true
        check(14, 14) shouldBe false
        check(19, 20) shouldBe false
        check(20, 21) shouldBe true
        check(25, 28) shouldBe false
      }

      "recognize the switch block boundary" in {
        val end  = conf.genesisEraEnd
        val `1h` = hours(1)
        runtime.isSwitchBoundary(end minus `1h`, end plus `1h`) shouldBe true
        runtime.isSwitchBoundary(end minus `1h`, end) shouldBe true
        runtime.isSwitchBoundary(end, end) shouldBe false
        runtime.isSwitchBoundary(end, end plus `1h`) shouldBe false
      }
    }
  }

  "validate" should {
    "reject a block received from a doppelganger" in {
      val runtime = genesisEraRuntime("Alice".some)
      val message =
        makeBlock("Alice", eraId = runtime.era.keyBlockHash, roundId = runtime.startTick)
      runtime.validate(message).value shouldBe Left(
        "The block is coming from a doppelganger."
      )
    }
    "reject a block received from a non-leader" in {
      val runtime = genesisEraRuntime(
        "Alice".some,
        leaderSequencer = mockSequencer("Bob")
      )
      val message = makeBlock(
        validator = "Charlie",
        eraId = runtime.era.keyBlockHash,
        roundId = runtime.startTick
      )
      runtime.validate(message).value shouldBe Left(
        "The block is not coming from the leader of the round."
      )
    }
    "reject a second block received from the leader in the same round" in (pending)
  }

  "initAgenda" when {
    "the validator is bonded in the era" should {
      "schedule the first round" in {
        val runtime = genesisEraRuntime(validator = "Alice".some)

        val agenda = runtime.initAgenda
        agenda should have size 1
        agenda.head.tick shouldBe runtime.startTick
        agenda.head.action shouldBe Agenda.StartRound(runtime.startTick)
      }

      "schedule the first round at the next available round exponent" in {
        // Say this validator is starting a bit late.
        val now = conf.genesisEraStart plus 5.hours
        val exp = 10

        implicit val clock = TestClock.frozen[Id](now)
        val runtime        = genesisEraRuntime(validator = "Alice".some, roundExponent = exp)

        val millisNext = Ticks.nextRound(runtime.startTick, exp)(Ticks(now.toEpochMilli))

        val agenda = runtime.initAgenda
        agenda should have size 1
        agenda.head.tick shouldBe millisNext
        agenda.head.action shouldBe Agenda.StartRound(Ticks(millisNext))
      }
    }

    "the validator is not bonded in the era" should {
      "not schedule anything" in {
        genesisEraRuntime("Anonymous".some).initAgenda shouldBe empty
        genesisEraRuntime(none).initAgenda shouldBe empty
      }
    }

    "the era is already over" should {
      "not schedule anything" in {
        implicit val clock = TestClock.frozen[Id](conf.genesisEraStart plus 700.days)
        genesisEraRuntime(validator = "Alice".some).initAgenda shouldBe empty
      }
    }
  }

  "handleMessage" when {
    "the validator is bonded" when {

      val leader = "Charlie"
      val runtime = genesisEraRuntime(
        "Alice".some,
        leaderSequencer = mockSequencer(leader),
        roundExponent = 1 // Every 2nd second.
      )

      "given a lambda message" when {

        "it is from the leader" should {
          "create a lambda response" in {
            val msg    = makeBlock(leader, runtime.era.keyBlockHash, runtime.startTick)
            val events = runtime.handleMessage(msg).written
            events should have size 1
            assertEvent(events) {
              case event: HighwayEvent.CreatedLambdaResponse =>
                event.message.parentBlock shouldBe msg.messageHash
            }
          }

          "remember that a lambda message was received in this round" in {
            // This will be relevant when for round exponent adjustments.
            pending
          }

          "reject further lambda messages in this round" in (pending)
        }

        "it is not from the leader" should {
          "reject the block" in {
            val msg = makeBlock("Bob", runtime.era.keyBlockHash, runtime.startTick)
            an[IllegalStateException] should be thrownBy {
              runtime.handleMessage(msg)
            }
          }
        }

        "it is a switch block" should {
          "create an era" in (pending)
        }

        "in a round which is not corresponding to the validator's current round ID" should {
          "not respond" in {
            val msg =
              makeBlock(leader, runtime.era.keyBlockHash, roundId = Ticks(runtime.startTick + 1))
            runtime.handleMessage(msg).written shouldBe empty
          }
        }

        "it is from the validator itself" should {
          "throw IllegalStateException" in {
            an[IllegalStateException] shouldBe thrownBy {
              val msg = makeBlock(validator = "Alice", runtime.era.keyBlockHash, runtime.startTick)
              runtime.handleMessage(msg)
            }
          }
        }
      }

      "given a ballot" when {
        "in the normal era period" should {
          "not respond" in {
            val msg = makeBallot(leader, runtime.era.keyBlockHash, runtime.startTick)
            runtime.handleMessage(msg).written shouldBe empty
          }
        }
        "in the post-era voting period" when {
          "coming from the leader" should {
            "create a lambda response" in (pending)
          }
          "coming from a non-leader" should {
            "not respond" in (pending)
          }
        }
      }
    }

    "the validator is not bonded" when {
      val leader = "Alice"
      val runtime = genesisEraRuntime(
        "Anonymous".some,
        leaderSequencer = mockSequencer(leader)
      )

      "given a lambda message" should {
        "not respond" in {
          val msg = makeBlock(leader, runtime.era.keyBlockHash, runtime.startTick)
          runtime.handleMessage(msg).written shouldBe empty
        }
      }

      "given a switch block" should {
        "create an era" in (pending)
      }
    }

    "the message is played back during initial sync" should {
      "not respond" in {
        val leader      = "Alice"
        val isSyncedRef = Ref.of[Id, Boolean](false)
        val runtime = genesisEraRuntime(
          "Bob".some,
          isSyncedRef = isSyncedRef,
          leaderSequencer = mockSequencer(leader)
        )
        val msg = makeBlock(leader, runtime.era.keyBlockHash, runtime.startTick)
        runtime.handleMessage(msg).written shouldBe empty
        isSyncedRef.set(true) // Works with `Id` only because of the `=> F[Boolean]`
        runtime.handleMessage(msg).written should not be empty
      }
    }
  }

  "handleAgenda" when {
    "given a StartRound action" when {
      "in the active period of the era" when {
        "the validator is the leader" should {
          val exponent    = 15
          val roundLength = Ticks.roundLength(exponent).millis
          val now         = conf.genesisEraStart plus (roundLength * 20)
          val roundId     = conf.toTicks(now)
          val nextRoundId =
            Ticks.nextRound(Ticks(conf.genesisEraStart.toEpochMilli), exponent)(roundId)

          implicit val clock = TestClock.frozen[Id](now)

          val runtime = genesisEraRuntime(
            "Alice".some,
            leaderSequencer = mockSequencer("Alice"),
            roundExponent = exponent
          )

          val (events, agenda) = runtime.handleAgenda(Agenda.StartRound(roundId)).run

          "create a lambda message" in {
            events should have size 1
            assertEvent(events) {
              case event: HighwayEvent.CreatedLambdaMessage =>
                event.message.roundId shouldBe roundId
            }
          }

          "not schedule more than 1 round" in {
            agenda should have size (2)
          }

          "schedule another round" in {
            assertAgenda(agenda) {
              case Agenda.DelayedAction(tick, start: Agenda.StartRound) =>
                tick shouldBe nextRoundId
                start.roundId shouldBe nextRoundId
            }
          }

          "schedule an omega message" in {
            assertAgenda(agenda) {
              case Agenda.DelayedAction(_, omega: Agenda.CreateOmegaMessage) =>
                omega.roundId shouldBe roundId
            }
          }

          "randomize the omega delay" in {
            val ticks: List[Long] = List
              .fill(10) {
                runtime.handleAgenda(Agenda.StartRound(roundId)).value.collect {
                  case Agenda.DelayedAction(tick, _: Agenda.CreateOmegaMessage) =>
                    tick
                }
              }
              .flatten

            ticks.toSet.size should be > 1

            forAll(ticks) { tick =>
              tick should be >= (roundId + (nextRoundId - roundId) * conf.omegaMessageTimeStart).toLong
              tick should be < (roundId + (nextRoundId - roundId) * conf.omegaMessageTimeEnd).toLong
            }
          }
        }

        "the validator is not leading" should {
          val runtime = genesisEraRuntime(
            "Alice".some,
            leaderSequencer = mockSequencer("Bob")
          )
          val (events, agenda) = runtime.handleAgenda(Agenda.StartRound(runtime.startTick)).run

          "not create a lambda message" in {
            events shouldBe empty
          }
          "schedule another round" in {
            forExactly(1, agenda) { a =>
              a.action shouldBe an[Agenda.StartRound]
            }
          }
          "schedule an omega message" in {
            forExactly(1, agenda) { a =>
              a.action shouldBe an[Agenda.CreateOmegaMessage]
            }
          }
        }
      }

      "right after the era-end" when {
        "no previous switch block has been seen" should {
          "create a switch block" in (pending)
        }
        "already found a switch block" should {
          "only create ballots" in (pending)
        }
      }

      "in the post-era voting period" when {
        "the validator is the leader" should {
          "a ballot instead of a lambda message" in (pending)
        }
        "the voting is still going" should {
          "schedule an omega message" in (pending)
          "schedule another round" in (pending)
        }
        "the voting period is over" should {
          "not schedule anything" in (pending)
        }
      }

      "during initial sync" should {
        val runtime = genesisEraRuntime(
          "Alice".some,
          leaderSequencer = mockSequencer("Alice"),
          isSyncedRef = Ref.of[Id, Boolean](false)
        )

        val (events, agenda) = runtime.handleAgenda(Agenda.StartRound(runtime.startTick)).run

        "not create a lambda message" in {
          events shouldBe empty
        }
        "schedule another round" in {
          forExactly(1, agenda) { a =>
            a.action shouldBe an[Agenda.StartRound]
          }
        }
        "schedule an omega message" in {
          forExactly(1, agenda) { a =>
            a.action shouldBe an[Agenda.CreateOmegaMessage]
          }
        }
      }

      "creating the lambda message takes longer than a round" should {
        "skip to the next active round" in {
          val exponent       = 15 // ~30s
          val roundLength    = Ticks.roundLength(exponent).millis
          val roundStart     = conf.genesisEraStart plus 60 * roundLength
          val now            = roundStart plus 3 * roundLength
          implicit val clock = TestClock.frozen[Id](now)

          val runtime = genesisEraRuntime(none, roundExponent = exponent)

          // Executing this round that was supposed to have been done a while ago.
          val roundId = conf.toTicks(roundStart)
          val agenda  = runtime.handleAgenda(Agenda.StartRound(roundId)).value

          agenda should have size 1
          assertAgenda(agenda) {
            case Agenda.DelayedAction(tick, Agenda.StartRound(nextRoundId)) =>
              val currentTick = conf.toTicks(now)
              tick should be > currentTick.toLong
              nextRoundId should be > currentTick.toLong
              nextRoundId should be > roundId + roundLength.toMillis
          }
        }
      }

      "crossing a booking block boundary" should {
        "pass the information to the message producer" in (pending)
      }
    }
    "given a CreateOmegaMessage action" when {
      "during initial sync" should {
        "not create an omega message" in {
          val runtime = genesisEraRuntime(
            "Alice".some,
            isSyncedRef = Ref.of[Id, Boolean](false)
          )
          val (events, agenda) =
            runtime.handleAgenda(Agenda.CreateOmegaMessage(runtime.startTick)).run

          events shouldBe empty
          agenda shouldBe empty
        }
      }
      "the era is active" should {
        "create an omega message" in {
          val runtime = genesisEraRuntime("Alice".some)
          val events =
            runtime.handleAgenda(Agenda.CreateOmegaMessage(runtime.startTick)).written

          assertEvent(events) {
            case HighwayEvent.CreatedOmegaMessage(_) =>
          }
        }
      }
      "in the post-era voting period" should {
        "create an omega message" in (pending)
      }
    }
  }
}

object EraRuntimeSpec {
  import cats.implicits._

  def validatorKey(name: String) =
    PublicKey(ByteString.copyFromUtf8(name))

  def makeBlock(validator: String, eraId: ByteString, roundId: Ticks) =
    Message.fromBlockSummary {
      BlockSummary()
        .withHeader(
          Block
            .Header()
            .withValidatorPublicKey(validatorKey(validator))
            .withKeyBlockHash(eraId)
            .withRoundId(roundId)
        )
        .withBlockHash(ByteString.copyFromUtf8(System.currentTimeMillis.toString))
    }.get

  def makeBallot(validator: String, eraId: ByteString, roundId: Ticks) =
    Message.fromBlockSummary {
      BlockSummary()
        .withHeader(
          Block
            .Header()
            .withMessageType(Block.MessageType.BALLOT)
            .withKeyBlockHash(eraId)
            .withRoundId(roundId)
            .withValidatorPublicKey(validatorKey(validator))
        )
        .withBlockHash(ByteString.copyFromUtf8(System.currentTimeMillis.toString))
    }.get

  def mockMessageProducer[F[_]: Applicative](validator: String) = new MessageProducer[F] {
    override val validatorId = validatorKey(validator)

    override def ballot(
        eraId: ByteString,
        roundId: Ticks,
        target: ByteString,
        justifications: Map[PublicKeyBS, Set[BlockHash]]
    ): F[Message.Ballot] =
      BlockSummary()
        .withHeader(
          Block
            .Header()
            .withMessageType(Block.MessageType.BALLOT)
            .withValidatorPublicKey(validatorId)
            .withParentHashes(List(target))
            .withJustifications(
              for {
                kv <- justifications.toList
                h  <- kv._2.toList
              } yield Block.Justification(kv._1, h)
            )
            .withRoundId(roundId)
            .withKeyBlockHash(eraId)
        )
        .pure[F]
        .map(Message.fromBlockSummary(_).get.asInstanceOf[Message.Ballot])

    override def block(
        eraId: ByteString,
        roundId: Ticks,
        mainParent: ByteString,
        justifications: Map[PublicKeyBS, Set[BlockHash]],
        isBookingBlock: Boolean
    ): F[Message.Block] =
      BlockSummary()
        .withHeader(
          Block
            .Header()
            .withValidatorPublicKey(validatorId)
            .withParentHashes(List(mainParent))
            .withJustifications(
              for {
                kv <- justifications.toList
                h  <- kv._2.toList
              } yield Block.Justification(kv._1, h)
            )
            .withRoundId(roundId)
            .withKeyBlockHash(eraId)
        )
        .pure[F]
        .map(Message.fromBlockSummary(_).get.asInstanceOf[Message.Block])
  }

  def mockSequencer(validator: String) = new LeaderSequencer {
    def apply[F[_]: MonadThrowable](era: Era): F[LeaderFunction] =
      ((_: Ticks) => validatorKey(validator)).pure[F]
  }
}
