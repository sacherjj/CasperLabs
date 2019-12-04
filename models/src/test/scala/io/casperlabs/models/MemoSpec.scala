package io.casperlabs.models
import monix.eval.Coeval
import org.scalatest.{FlatSpec, Matchers}
import io.casperlabs.shared.Log

class MemoSpec extends FlatSpec with Matchers with Log.LogId {

  behavior of "Memo"

  it should "memoize the result" in {
    var timesExecuted = 0
    val random = new Memo[Int](Coeval.delay {
      timesExecuted += 1
      9
    })
    assert(timesExecuted == 0)
    assert(random.get.value() == 9)
    assert(timesExecuted == 1)
    assert(random.get.value() == 9)
    assert(timesExecuted == 1)
  }

}
