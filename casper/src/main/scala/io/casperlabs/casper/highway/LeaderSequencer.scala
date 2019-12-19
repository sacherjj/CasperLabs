package io.casperlabs.casper.highway

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
}
