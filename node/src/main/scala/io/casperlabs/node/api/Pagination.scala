package io.casperlabs.node.api

import com.google.protobuf.ByteString
import io.casperlabs.comm.ServiceError.InvalidArgument
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.storage.block.BlockStorage.DeployHash

import scala.util.{Failure, Try}

trait Pagination {
  type PageTokenParams

  type PageSize  = Int
  type PageToken = String

  type RequestWithPagination = {
    val pageSize: PageSize
    val pageToken: PageToken
  }

  def parsePageToken(
      requestWithPagination: RequestWithPagination
  ): Try[(PageSize, PageTokenParams)]

  def createNextPageToken(pageTokenParamsOpt: Option[PageTokenParams]): PageToken
}

object DeployInfoPagination extends Pagination {
  val MAXSIZE = 50

  override type PageTokenParams = (Long, DeployHash)

  override def parsePageToken(
      request: RequestWithPagination
  ): Try[(PageSize, PageTokenParams)] = {
    val pageSize = math.max(0, math.min(request.pageSize, MAXSIZE))
    if (request.pageToken.isEmpty) {
      Try { (pageSize, (Long.MaxValue, ByteString.EMPTY)) }
    } else {
      Try {
        request.pageToken
          .split(':')
          .map(Base16.decode)
      }.filter(_.length == 2) map (
          arr =>
            (
              pageSize,
              (
                BigInt(arr(0)).longValue(),
                ByteString.copyFrom(arr(1))
              )
            )
        ) match {
        case Failure(_) =>
          Failure(
            InvalidArgument(
              "Expected pageToken encoded as {lastTimeStamp}:{lastDeployHash}. Where both are hex encoded."
            )
          )
        case x => x
      }
    }
  }

  override def createNextPageToken(
      pageTokenParamsOpt: Option[PageTokenParams]
  ): PageToken =
    pageTokenParamsOpt match {
      case None => ""
      case Some(pageTokenParams) =>
        val (lastTimestamp, lastDeployHash) = pageTokenParams
        val lastTimestampBase16 =
          Base16.encode(
            BigInt(lastTimestamp).toByteArray
          )
        val lastDeployHashBase16 = Base16.encode(lastDeployHash.toByteArray)
        s"$lastTimestampBase16:$lastDeployHashBase16"
    }
}
