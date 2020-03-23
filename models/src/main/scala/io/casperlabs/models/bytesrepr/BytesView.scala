package io.casperlabs.models.bytesrepr

import java.nio.{ByteBuffer, ByteOrder}

/** Captures a view (slice) of an Array without re-allocating bytes from the
  * underlying array. The reason for introducing this class instead of using
  * `ByteBuffer` directly is because `ByteBuffer` is mutable.
  */
class BytesView private (
    private val underlying: Array[Byte],
    private val start: Int,
    val length: Int
) {

  /** Creates two new views, one with the first `n` elements, and one with the
    * remaining elements; if possible.
    */
  def safeTake(n: Int): Option[(BytesView, BytesView)] =
    if (n <= length)
      Some(
        (
          new BytesView(underlying, start + n, length - n),
          new BytesView(underlying, start, n)
        )
      )
    else None

  def pop: Option[(BytesView, Byte)] =
    if (length >= 1)
      Some(
        (
          new BytesView(underlying, start + 1, length - 1),
          underlying(start)
        )
      )
    else None

  def toByteBuffer: ByteBuffer =
    ByteBuffer.wrap(underlying, start, length).order(ByteOrder.LITTLE_ENDIAN)

  def toArray: Array[Byte] = underlying.slice(start, start + length)

  def nonEmpty: Boolean = length > 0
}

object BytesView {
  def apply(underlying: Array[Byte], start: Int): Option[BytesView] =
    if (start >= 0 && start < underlying.length)
      Some(new BytesView(underlying, start, underlying.length - start))
    else None

  def apply(underlying: Array[Byte]): BytesView = new BytesView(underlying, 0, underlying.length)
}
