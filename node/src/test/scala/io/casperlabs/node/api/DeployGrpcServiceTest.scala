package io.casperlabs.node.api

import io.casperlabs.crypto.codec.Base16
import io.casperlabs.ipc
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{FlatSpec, Matchers}

import DeployGrpcService.splitPath

class DeployGrpcServiceTest extends FlatSpec with Matchers {

  "toKey" should "convert a hash-type key successfully" in {
    val keyValue = randomBytes(32)
    val keyType  = "hash"

    val maybeKey = attemptToKey(keyType, keyValue)
    maybeKey.isRight shouldBe true

    val ipc.Key(key) = maybeKey.right.get
    key.isHash shouldBe true

    val ipc.KeyHash(hash) = key.hash.get
    Base16.encode(hash.toByteArray) shouldBe keyValue
  }

  it should "convert a uref-type key successfully" in {
    val keyValue = randomBytes(32)
    val keyType  = "uref"

    val maybeKey = attemptToKey(keyType, keyValue)
    maybeKey.isRight shouldBe true

    val ipc.Key(key) = maybeKey.right.get
    key.isUref shouldBe true

    val ipc.KeyURef(uref) = key.uref.get
    Base16.encode(uref.toByteArray) shouldBe keyValue
  }

  it should "convert an address-type key successfully" in {
    val keyValue = randomBytes(20)
    val keyType  = "address"

    val maybeKey = attemptToKey(keyType, keyValue)
    maybeKey.isRight shouldBe true

    val ipc.Key(key) = maybeKey.right.get
    key.isAccount shouldBe true

    val ipc.KeyAddress(address) = key.account.get
    Base16.encode(address.toByteArray) shouldBe keyValue
  }

  it should "fail for any invalid key type" in {
    val keyValue = randomBytes(32)
    val keyType  = util.Random.alphanumeric.take(10).mkString

    val maybeKey = attemptToKey(keyType, keyValue)
    maybeKey.isLeft shouldBe true
  }

  "splitPath" should "split on '/' and exclude empty components" in {
    val pathA = "a/b/c"
    val pathB = ""
    val pathC = "///a//b///////"

    splitPath(pathA) shouldBe Seq("a", "b", "c")
    splitPath(pathB) shouldBe Seq.empty[String]
    splitPath(pathC) shouldBe Seq("a", "b")
  }

  private def randomBytes(length: Int): String =
    Base16.encode(Array.fill(length)(util.Random.nextInt(256).toByte))

  private def attemptToKey(keyType: String, keyValue: String): Either[Throwable, ipc.Key] =
    DeployGrpcService.toKey[Task](keyType, keyValue).attempt.runSyncUnsafe()
}
