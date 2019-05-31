package io.casperlabs.client

import io.casperlabs.crypto.codec.Base16
import java.math.BigInteger
import scalapb.GeneratedMessage
import scalapb.descriptors.{FieldDescriptor, PByteString, PEmpty, PMessage, PRepeated, PValue}
import scalapb.textformat.{TextGenerator}

/** Based on what scalapb `toProtoString` does but using Base16 for bytes. */
object Printer {
  def printToUnicodeString(m: GeneratedMessage) = {
    val out = new TextGenerator(singleLine = false, escapeNonAscii = false)
    print(m.toPMessage, out)
    out.result()
  }

  def print(p: PMessage, out: TextGenerator): Unit =
    p.value.toSeq.sortBy(_._1.number).foreach {
      case (fd, value) => printField(fd, value, out)
    }

  def printField(fd: FieldDescriptor, value: PValue, out: TextGenerator): Unit = value match {
    case PRepeated(values) =>
      values.foreach(v => printSingleField(fd, v, out))
    case PEmpty =>
    case _ =>
      printSingleField(fd, value, out)
  }

  def printSingleField(fd: FieldDescriptor, value: PValue, out: TextGenerator) = {
    out.add(fd.name)
    value match {
      case PMessage(_) =>
        out.addNewLine(" {").indent()
        printFieldValue(fd, value, out)
        out.outdent().addNewLine("}")
      case _ =>
        out.add(": ")
        printFieldValue(fd, value, out)
        out.addNewLine("")
    }
  }

  def printFieldValue(fd: FieldDescriptor, value: PValue, out: TextGenerator): Unit =
    value match {
      case scalapb.descriptors.PInt(v) =>
        if (fd.protoType.isTypeUint32 || fd.protoType.isTypeFixed32)
          out.add(unsignedToString(v))
        else
          out.add(v.toString)
      case scalapb.descriptors.PLong(v) =>
        if (fd.protoType.isTypeUint64 || fd.protoType.isTypeFixed64)
          out.add(unsignedToString(v))
        else
          out.add(v.toString)
      case scalapb.descriptors.PBoolean(v) =>
        out.add(v.toString)
      case scalapb.descriptors.PFloat(v) =>
        out.add(v.toString)
      case scalapb.descriptors.PDouble(v) =>
        out.add(v.toString)
      case scalapb.descriptors.PEnum(v) =>
        if (!v.isUnrecognized)
          out.add(v.name)
        else
          out.add(v.number.toString)
      case e: scalapb.descriptors.PMessage =>
        print(e, out)
      case scalapb.descriptors.PString(v) =>
        out
          .add("\"")
          .addMaybeEscape(v)
          .add("\"")
      case scalapb.descriptors.PByteString(v) =>
        out
          .add("\"")
          //.add(textformat.TextFormatUtils.escapeBytes(v))
          .add(Base16.encode(v.toByteArray))
          .add("\"")
      case scalapb.descriptors.PRepeated(_) =>
        throw new RuntimeException("Should not happen.")
      case scalapb.descriptors.PEmpty =>
        throw new RuntimeException("Should not happen.")
    }

  /** Convert an unsigned 32-bit integer to a string. */
  def unsignedToString(value: Int): String =
    if (value >= 0) java.lang.Integer.toString(value)
    else java.lang.Long.toString(value & 0X00000000FFFFFFFFL)

  /** Convert an unsigned 64-bit integer to a string. */
  def unsignedToString(value: Long): String =
    if (value >= 0) java.lang.Long.toString(value)
    else BigInteger.valueOf(value & 0X7FFFFFFFFFFFFFFFL).setBit(63).toString
}
