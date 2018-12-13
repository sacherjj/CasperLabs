package io.casperlabs.casper.genesis.contracts

import io.casperlabs.casper.util.ProtoUtil.compiledSourceDeploy
import io.casperlabs.rholang.interpreter.accounting
import io.casperlabs.rholang.interpreter.storage.StoragePrinter
import io.casperlabs.rholang.mint.{MakeMint, MakeMintTest}

import monix.execution.Scheduler.Implicits.global

import org.scalatest.{FlatSpec, Matchers}

class MakeMintSpec extends FlatSpec with Matchers {
  val runtime = TestSetUtil.runtime
  val tests   = TestSetUtil.getTests("../casper/src/test/rholang/MakeMintTest.rho").toList

  val deploys = List(
    StandardDeploys.nonNegativeNumber,
    StandardDeploys.makeMint
  )
  TestSetUtil.runTestsWithDeploys(MakeMintTest, deploys, runtime)
  val tuplespace = StoragePrinter.prettyPrint(runtime.space.store)

  "MakeMint rholang contract" should tests.head in {
    TestSetUtil.testPassed(tests.head, tuplespace) should be(true)
  }

  tests.tail.map(test => {
    it should test in {
      TestSetUtil.testPassed(test, tuplespace) should be(true)
    }
  })
}
