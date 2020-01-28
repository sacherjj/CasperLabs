package io.casperlabs.smartcontracts.bytesrepr

import java.nio.{ByteBuffer, ByteOrder}

/** Captures a view (slice) of an Array without re-allocating bytes from the
  * underlying array. The reason for introducing this class instead of using
  * `ByteBuffer` directly is because `ByteBuffer` is mutable.
  */
class BytesView(val underlying: Array[Byte], val start: Int, val length: Int) {

  /** Creates two new views, one with the first `n` elements, and one with the
    * remaining elements; if possible.
    */
  def safeTake(n: Int): Option[(BytesView, BytesView)] =
    if (n <= length)
      Some(
        (
          BytesView(underlying, start, n),
          BytesView(underlying, start + n, length - n)
        )
      )
    else None

  def pop: Option[(Byte, BytesView)] =
    if (length >= 1 && start < underlying.length && start >= 0)
      Some(
        (
          underlying(start),
          BytesView(underlying, start + 1, length - 1)
        )
      )
    else None

  def buffer: ByteBuffer = ByteBuffer.wrap(underlying, start, length).order(ByteOrder.LITTLE_ENDIAN)

  def toArray: Array[Byte] = underlying.slice(start, start + length)

  def nonEmpty: Boolean = length > 0
}

object BytesView {
  def apply(underlying: Array[Byte], start: Int, length: Int): BytesView =
    new BytesView(underlying, start, length)

  def apply(underlying: Array[Byte]): BytesView = new BytesView(underlying, 0, underlying.length)
}
