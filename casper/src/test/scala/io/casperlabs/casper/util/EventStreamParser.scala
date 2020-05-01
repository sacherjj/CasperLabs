package io.casperlabs.casper.util

import java.nio.file.Paths

import cats.Functor
import cats.syntax.functor._
import io.casperlabs.casper.consensus.info.Event
import io.casperlabs.crypto.codec.Base64
import io.casperlabs.shared.FilesAPI

object EventStreamParser {

  /**
    * Parses a file as a stream of events emitted by the node.
    * @param fileName
    * @tparam F
    * @return List of events ordered as emitted by the node.
    */
  def fromFile[F[_]: FilesAPI: Functor](fileName: String): F[List[Event]] =
    FilesAPI[F]
      .readString(Paths.get(getClass.getResource(fileName).getPath))
      .map(
        _.split("\n").toList
          .map(str => {
            Event.parseFrom(Base64.tryDecode(str).get)
          })
      )

}
