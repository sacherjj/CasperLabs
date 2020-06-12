package io.casperlabs.casper.highway

import cats.implicits._
import cats.effect.{Resource, Timer}
import cats.effect.concurrent.Ref
import monix.eval.Task
import org.scalatest._
import scala.concurrent.duration.FiniteDuration
import io.casperlabs.crypto.Keys.PublicKeyBS
import io.casperlabs.comm.gossiping.WaitHandle
import io.casperlabs.comm.gossiping.relaying.BlockRelaying
import io.casperlabs.storage.{BlockHash, SQLiteStorage}
import io.casperlabs.shared.Log

trait HighwayNetworkFixture { self: HighwayFixture =>

  abstract class RelayFixture(
      length: FiniteDuration,
      validator: String,
      initRoundExponent: Int,
      supervisorsRef: Ref[Task, Map[String, EraSupervisor[Task]]],
      isSyncedRef: Ref[Task, Boolean],
      conf: HighwayConf = defaultConf,
      // Optionally restrict validator communication channels.
      routing: Map[String, Set[String]] = Map.empty,
      printLevel: Log.Level = Log.Level.Error
  )(
      implicit
      timer: Timer[Task],
      db: SQLiteStorage.CombinedStorage[Task]
  ) extends Fixture(
        length = length,
        validator = validator,
        initRoundExponent = initRoundExponent,
        conf = conf,
        isSyncedRef = isSyncedRef,
        printLevel = printLevel
      ) (timer, db) {

    val relayedRef: Ref[Task, Set[BlockHash]] = Ref.unsafe(Set.empty)

    override lazy val blockRelaying = new BlockRelaying[Task] {
      override def relay(hashes: List[BlockHash]): Task[WaitHandle[Task]] =
        for {
          _           <- relayedRef.update(_ ++ hashes)
          blocks      <- hashes.traverse(h => db.getBlockMessage(h).map(_.get))
          supervisors <- supervisorsRef.get
          mask        = routing.getOrElse(validator, supervisors.keySet) - validator
          // Notify other supervisors. Doing it on a fiber because when done
          // synchronously it caused an unexplicable error in the first era
          // after genesis where validators didn't find the blocks they clearly
          // saved into the DAG, ones they created themselves for example.
          // It seemed thought that with fibers and low round exponents there
          // are race conditions between handling incoming messages vs creating own.
          fiber <- supervisors
                    .filterKeys(mask)
                    .values
                    .toList
                    .traverse(s => blocks.traverse(b => s.validateAndAddBlock(b)))
                    .void
                    .start
        } yield fiber.join
    }
  }

  abstract class NetworkFixture(
      validatorDatabases: List[(String, SQLiteStorage.CombinedStorage[Task])]
  ) extends FixtureLike {

    // Test all networked fixtures.
    override def test: Task[Unit] =
      network.use(_.traverse(_.test).void)

    def network: Resource[Task, List[RelayFixture]] =
      for {
        // Don't create messages until we add all supervisors to this collection,
        // otherwise they might miss some messages and there's no synchronizer here.
        isSyncedRef    <- Resource.liftF(Ref[Task].of(false))
        supervisorsRef <- Resource.liftF(Ref[Task].of(Map.empty[String, EraSupervisor[Task]]))
        fixtures = validatorDatabases.map {
          case (validator, db) =>
            makeRelayFixture(validator, db, supervisorsRef, isSyncedRef)
        }
        validators  = validatorDatabases.unzip._1
        supervisors <- fixtures.traverse(_.makeSupervisor())
        _ <- Resource.liftF {
              supervisorsRef.set(validators zip supervisors toMap) *> isSyncedRef.set(true)
            }
      } yield fixtures

    def makeRelayFixture(
        validator: String,
        db: SQLiteStorage.CombinedStorage[Task],
        supervisorsRef: Ref[Task, Map[String, EraSupervisor[Task]]],
        isSyncedRef: Ref[Task, Boolean]
    ): RelayFixture
  }

}
