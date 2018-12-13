package io.casperlabs.casper.genesis.contracts

import io.casperlabs.casper.util.ProtoUtil.compiledSourceDeploy
import io.casperlabs.rholang.interpreter.accounting
import io.casperlabs.rholang.interpreter.storage.StoragePrinter
import io.casperlabs.rholang.collection.Either
import io.casperlabs.rholang.math.NonNegativeNumber
import io.casperlabs.rholang.mint.MakeMint
import io.casperlabs.rholang.proofofstake.{MakePoS, MakePoSTest}

import monix.execution.Scheduler.Implicits.global

import org.scalatest.{FlatSpec, Matchers}

class MakePoSSpec extends FlatSpec with Matchers {
  val runtime = TestSetUtil.runtime
  val tests   = TestSetUtil.getTests("../casper/src/test/rholang/MakePoSTest.rho").toList

  val deploys = List(
    StandardDeploys.nonNegativeNumber,
    StandardDeploys.makeMint,
    StandardDeploys.either,
    StandardDeploys.makePoS
  )
  TestSetUtil.runTestsWithDeploys(MakePoSTest, deploys, runtime)
  val tuplespace = StoragePrinter.prettyPrint(runtime.space.store)

  "MakePoS rholang contract" should tests.head in {
    TestSetUtil.testPassed(tests.head, tuplespace) should be(true)
  }

  tests.tail.map(test => {
    it should test in {
      TestSetUtil.testPassed(test, tuplespace) should be(true)
    }
  })
}
