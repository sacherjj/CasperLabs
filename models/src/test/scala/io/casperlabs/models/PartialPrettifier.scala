package io.casperlabs.models

import org.scalactic.Prettifier
import com.google.protobuf.ByteString
import scala.util.Success
import scala.collection.{mutable, GenMap}
import scala.collection.GenTraversable
import _root_.cats.instances.`package`.byte

object PartialPrettifier {
  // Shameless copy-paste of Scalactic's Prettifier that injects special pretty-printer for ByteString.
  def apply(pp: PartialFunction[Any, String]): Prettifier = Prettifier {
    case x if pp.isDefinedAt(x) => pp(x)
    case Some(e)                => "Some(" + apply(pp)(e) + ")"
    case Success(e)             => "Success(" + apply(pp)(e) + ")"
    case Left(e)                => "Left(" + apply(pp)(e) + ")"
    case Right(e)               => "Right(" + apply(pp)(e) + ")"
    case anArray: Array[_]      => "Array(" + (anArray.map(apply(pp)(_))).mkString(", ") + ")"
    case aWrappedArray: mutable.WrappedArray[_] =>
      "Array(" + (aWrappedArray.map(apply(pp)(_))).mkString(", ") + ")"
    case anArrayOps: mutable.ArrayOps[_] =>
      "Array(" + (anArrayOps.map(apply(pp)(_))).mkString(", ") + ")"
    case aGenMap: GenMap[_, _] =>
      aGenMap.stringPrefix + "(" +
        (aGenMap.toIterator
          .map {
            case (key, value) => // toIterator is needed for consistent ordering
              apply(pp)(key) + " -> " + apply(pp)(value)
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
          .map(apply(pp)(_))
          .mkString(", ") + ")" // toIterator is needed for consistent ordering
    // SKIP-SCALATESTJS-START
    case javaCol: java.util.Collection[_] =>
      // By default java collection follows http://download.java.net/jdk7/archive/b123/docs/api/java/util/AbstractCollection.html#toString()
      // let's do our best to prettify its element when it is not overriden
      import scala.collection.JavaConverters._
      val theToString = javaCol.toString
      if (theToString.startsWith("[") && theToString.endsWith("]"))
        "[" + javaCol.iterator().asScala.map(apply(pp)(_)).mkString(", ") + "]"
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
            apply(pp)(entry.getKey) + "=" + apply(pp)(entry.getValue)
          }
          .mkString(", ") + "}"
      else
        theToString
    case tuple2: Tuple2[_, _] =>
      "(" ++ apply(pp)(tuple2._1) ++ ", " ++ apply(pp)(tuple2._2) ++ ")"
    case other => Prettifier.default(other)
  }
}
