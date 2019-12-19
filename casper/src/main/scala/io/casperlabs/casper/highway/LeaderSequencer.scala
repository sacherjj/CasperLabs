package io.casperlabs.casper.highway

import cats.data.NonEmptyList
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS}
import io.casperlabs.casper.consensus.Bond
import java.security.SecureRandom
import java.nio.{ByteBuffer, ByteOrder}

object LeaderSequencer {

  /** Concatentate all the magic bits into a byte array,
    * padding them with zeroes on the right.
    */
  def toByteArray(bits: Seq[Boolean]): Array[Byte] = {
    val size   = bits.size
    val pad    = 8 - size % 8
    val padded = bits.padTo(size + pad, false)
    val arr    = Array.fill(padded.size / 8)(0)
    padded.zipWithIndex.foreach {
      case (bit, i) =>
        val a = i / 8
        val b = 7 - i % 8
        val s = (if (bit) 1 else 0) << b
        arr(a) = arr(a) | s
    }
    arr.map(_.toByte)
  }

  def seed(parentSeed: Array[Byte], magicBits: Seq[Boolean]) =
    Blake2b256.hash(parentSeed ++ toByteArray(magicBits))

  /** Make a function that assigns a leader to each round, deterministically,
    * with a relative frequency based on their weight. */
  def makeSequencer(leaderSeed: Array[Byte], bonds: NonEmptyList[Bond]): Ticks => PublicKeyBS = {
    val validators = bonds.toList.toVector.map { x =>
      PublicKey(x.validatorPublicKey) -> BigInt(x.getStake.value)
    }
    // Using BigDecimal to be able to multiply with a Double later.
    val total = BigDecimal(validators.map(_._2).sum)

    // The auction should not allow 0 bids, but if it was, there would be no way to pick between them.
    require(validators.forall(_._2 > 0), "Bonds must be positive.")

    // Given a target sum of bonds, seek the validator with a total cumulative weight in that range.
    def seek(target: BigInt, i: Int = 0, acc: BigInt = 0): PublicKeyBS = {
      val b = validators(i)._2
      // Using > instead of >= so a validator has the lower, but not the upper extremum.
      if (acc + b > target || i == validators.size - 1)
        validators(i)._1
      else
        seek(target, i + 1, acc + b)
    }

    (tick: Ticks) => {
      // On Linux SecureRandom uses NativePRNG, and ignores the seed.
      // Re-seeding also doesn't reset the seed, just augments it, so a new instance is required.
      // https://stackoverflow.com/questions/50107982/rhe-7-not-respecting-java-secure-random-seed
      // NODE-1095: Find a more secure, cross platform algorithm.
      val random = SecureRandom.getInstance("SHA1PRNG", "SUN")
      // Ticks need to be deterministic, so each time we have to reset the seed.
      val tickSeed = leaderSeed ++ longToBytesLittleEndian(tick)
      random.setSeed(tickSeed)
      // Pick a number between [0, 1) and use it to find a validator.
      // NODE-1096: If possible generate a random BigInt directly, without involving a Double.
      val r = BigDecimal.valueOf(random.nextDouble())
      // Integer arithmetic is supposed to be safer than Double.
      val t = (total * r).toBigInt
      // Find the first validator over the target.
      seek(t)
    }
  }

  private def longToBytesLittleEndian(i: Long): Array[Byte] =
    ByteBuffer
      .allocate(8)
      .order(ByteOrder.LITTLE_ENDIAN)
      .putLong(i)
      .array
}
