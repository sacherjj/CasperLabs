package io.casperlabs.casper.highway

import cats.implicits._
import monix.eval.Task
import org.scalatest._
import scala.concurrent.duration._
import io.casperlabs.casper.highway.mocks.MockMessageProducer

class BaseBlockSpec extends FlatSpec with Matchers with Inspectors with HighwayFixture {

  behavior of "EraRuntime"

  it should "not participate in eras that aren't part of the main chain" in testFixture {
    implicit timer => implicit db =>
      new Fixture(
        length = 3 * eraDuration,
        initRoundExponent = 15 // ~ 8 hours
      ) {
        val thisProducer  = messageProducer
        val otherProducer = new MockMessageProducer[Task]("Bob")

        // 2 weeks for genesis, then 1 week for the descendants.
        // e0 - e1
        //    \ e2
        override def test =
          for {
            _  <- insertGenesis()
            e0 <- addGenesisEra()
            k1 <- e0.block(thisProducer, e0.keyBlockHash)
            k2 <- e0.block(otherProducer, e0.keyBlockHash)

            // The LFB goes towards k1, orphaning the other key block k2.
            _ <- db.markAsFinalized(k1, Set.empty, Set(k2))

            // But the other validator doesn't see this and produces an era e2 on top of k2.
            // This validator should not produce messages in the era which it knows is not
            // on the path of the LFB.
            e1 <- e0.addChildEra(k1)
            e2 <- e0.addChildEra(k2)
            r1 <- makeRuntime(e1)
            r2 <- makeRuntime(e2)

            // This validator should not have scheduled actions for the orphaned era.
            a1 <- r1.initAgenda
            _  = a1 should not be empty
            a2 <- r2.initAgenda
            _  = a2 shouldBe empty

            // This validator should not react to lambda messages in the orphaned era.

          } yield {}
      }
  }
}
