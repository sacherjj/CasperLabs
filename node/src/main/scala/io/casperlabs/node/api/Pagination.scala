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

  def createPageToken(pageTokenParamsOpt: Option[PageTokenParams]): PageToken
}

object DeployInfoPagination extends Pagination {
  val MAXSIZE   = 50
  val NEXT_PAGE = "N"
  val PREV_PAGE = "P"

  override type PageTokenParams = (Long, DeployHash, Boolean)

  override def parsePageToken(
      request: RequestWithPagination
  ): Try[(PageSize, PageTokenParams)] = {
    val pageSize = math.max(0, math.min(request.pageSize, MAXSIZE))
    if (request.pageToken.isEmpty) {
      Try { (pageSize, (Long.MaxValue, ByteString.EMPTY, true)) }
    } else {
      Try {
        request.pageToken
          .split(':')
          .map(Base16.decode)
      }.filter(_.length == 3) map (
          arr => {
            val next =
              if (new String(arr(2)) == PREV_PAGE) false
              else true
            (
              pageSize,
              (
                BigInt(arr(0)).longValue(),
                ByteString.copyFrom(arr(1)),
                next
              )
            )
          }
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

  override def createPageToken(
      pageTokenParamsOpt: Option[PageTokenParams]
  ): PageToken =
    pageTokenParamsOpt match {
      case None => ""
      case Some(pageTokenParams) =>
        val (lastTimestamp, lastDeployHash, next) = pageTokenParams
        val lastTimestampBase16 =
          Base16.encode(
            BigInt(lastTimestamp).toByteArray
          )
        val lastDeployHashBase16 = Base16.encode(lastDeployHash.toByteArray)
        val nextEncoded = if (next) {
          Base16.encode(NEXT_PAGE.getBytes())
        } else {
          Base16.encode(PREV_PAGE.getBytes())
        }
        s"$lastTimestampBase16:$lastDeployHashBase16:$nextEncoded"
    }
}
