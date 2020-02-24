package io.casperlabs.casper.util

import org.scalactic.Prettifier
import io.casperlabs.casper.PrettyPrinter
import com.google.protobuf.ByteString
import scala.util.Success
import scala.collection.{mutable, GenMap}
import scala.collection.GenTraversable
import _root_.cats.instances.`package`.byte

trait ByteStringPrettifier {
  // Shameless copy-paste of Scalactic's Prettifier that injects special pretty-printer for ByteString.
  implicit val byteStringPrettifier: Prettifier = Prettifier {
    case bs: ByteString    => PrettyPrinter.buildString(bs)
    case Some(e)           => "Some(" + byteStringPrettifier(e) + ")"
    case Success(e)        => "Success(" + byteStringPrettifier(e) + ")"
    case Left(e)           => "Left(" + byteStringPrettifier(e) + ")"
    case Right(e)          => "Right(" + byteStringPrettifier(e) + ")"
    case anArray: Array[_] => "Array(" + (anArray.map(byteStringPrettifier(_))).mkString(", ") + ")"
    case aWrappedArray: mutable.WrappedArray[_] =>
      "Array(" + (aWrappedArray.map(byteStringPrettifier(_))).mkString(", ") + ")"
    case anArrayOps: mutable.ArrayOps[_] =>
      "Array(" + (anArrayOps.map(byteStringPrettifier(_))).mkString(", ") + ")"
    case aGenMap: GenMap[_, _] =>
      aGenMap.stringPrefix + "(" +
        (aGenMap.toIterator
          .map {
            case (key, value) => // toIterator is needed for consistent ordering
              byteStringPrettifier(key) + " -> " + byteStringPrettifier(value)
          })
          .mkString(", ") + ")"
    case aGenTraversable: GenTraversable[_] =>
      val isSelf =
        if (aGenTraversable.size == 1) {
          aGenTraversable.head match {
            case ref: AnyRef => ref eq aGenTraversable
            case other       => other == aGenTraversable
          }
        } else
          false
      if (isSelf)
        aGenTraversable.toString
      else
        aGenTraversable.stringPrefix + "(" + aGenTraversable.toIterator
          .map(byteStringPrettifier(_))
          .mkString(", ") + ")" // toIterator is needed for consistent ordering
    // SKIP-SCALATESTJS-START
    case javaCol: java.util.Collection[_] =>
      // By default java collection follows http://download.java.net/jdk7/archive/b123/docs/api/java/util/AbstractCollection.html#toString()
      // let's do our best to prettify its element when it is not overriden
      import scala.collection.JavaConverters._
      val theToString = javaCol.toString
      if (theToString.startsWith("[") && theToString.endsWith("]"))
        "[" + javaCol.iterator().asScala.map(byteStringPrettifier(_)).mkString(", ") + "]"
      else
        theToString
    case javaMap: java.util.Map[_, _] =>
      // By default java map follows http://download.java.net/jdk7/archive/b123/docs/api/java/util/AbstractMap.html#toString()
      // let's do our best to prettify its element when it is not overriden
      import scala.collection.JavaConverters._
      val theToString = javaMap.toString
      if (theToString.startsWith("{") && theToString.endsWith("}"))
        "{" + javaMap.entrySet.iterator.asScala
          .map { entry =>
            byteStringPrettifier(entry.getKey) + "=" + byteStringPrettifier(entry.getValue)
          }
          .mkString(", ") + "}"
      else
        theToString
    case tuple2: Tuple2[_, _] =>
      "(" ++ byteStringPrettifier(tuple2._1) ++ ", " ++ byteStringPrettifier(tuple2._2) ++ ")"
    case other => Prettifier.default(other)
  }
}

object ByteStringPrettifier extends ByteStringPrettifier
