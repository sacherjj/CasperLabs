package io.casperlabs.node.api

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.comm.ServiceError.InvalidArgument
import io.casperlabs.crypto.codec.Base16

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
  case class DeployInfoPageTokenParams(
      lastTimeStamp: Long,
      lastDeployHash: ByteString,
      isNext: Boolean
  )
  val MAXSIZE   = 50
  val NEXT_PAGE = "N"
  val PREV_PAGE = "P"

  override type PageTokenParams = DeployInfoPageTokenParams

  override def parsePageToken(
      request: RequestWithPagination
  ): Try[(PageSize, PageTokenParams)] = {
    val pageSize = math.max(0, math.min(request.pageSize, MAXSIZE))
    if (request.pageToken.isEmpty) {
      Try { (pageSize, DeployInfoPageTokenParams(Long.MaxValue, ByteString.EMPTY, isNext = true)) }
    } else {
      Try {
        request.pageToken
          .split(':')
          .map(Base16.decode)
      }.filter(_.length == 3) map (
          arr => {
            val isNext =
              if (new String(arr(2)) == PREV_PAGE) false
              else true
            (
              pageSize,
              DeployInfoPageTokenParams(
                BigInt(arr(0)).longValue(),
                ByteString.copyFrom(arr(1)),
                isNext
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
        val lastTimestampBase16 =
          Base16.encode(
            BigInt(pageTokenParams.lastTimeStamp).toByteArray
          )
        val lastDeployHashBase16 = Base16.encode(pageTokenParams.lastDeployHash.toByteArray)
        val nextEncoded = if (pageTokenParams.isNext) {
          Base16.encode(NEXT_PAGE.getBytes())
        } else {
          Base16.encode(PREV_PAGE.getBytes())
        }
        s"$lastTimestampBase16:$lastDeployHashBase16:$nextEncoded"
    }

  /**
    * Compute the nextPageToken and prevPageToken.
    *
    * If `deploys` is not empty, then the `nextPageToken` can be generated from the last element of `deploys`,
    * and the `prevPageToken` can generate from the first element of `deploys`.
    *
    * If `deploys` is empty and we are fetching the next page, then the `prevPageToken` should be the MAX_CURSOR,
    * and nextPageToken is "", to indicate there is no more elements, else if we are fetching the previous page,
    * then the prevPageToken should be "", and nextPageToken should be the MIN_CURSOR.
    */
  def createNextAndPrePageToken(
      deploys: List[Deploy],
      pageTokenParams: PageTokenParams
  ): (PageToken, PageToken) =
    if (deploys.isEmpty) {
      if (pageTokenParams.isNext) {
        (
          "",
          DeployInfoPagination.createPageToken(
            Some(DeployInfoPageTokenParams(Long.MinValue, ByteString.EMPTY, isNext = false))
          )
        )
      } else {
        (
          DeployInfoPagination.createPageToken(
            Some(DeployInfoPageTokenParams(Long.MaxValue, ByteString.EMPTY, isNext = true))
          ),
          ""
        )
      }
    } else {
      val nextPageToken = DeployInfoPagination.createPageToken(
        deploys.lastOption
          .map(
            d =>
              DeployInfoPageTokenParams(
                d.getHeader.timestamp,
                d.deployHash,
                isNext = true
              )
          )
      )
      val prevPageToken = createPageToken(
        deploys.headOption
          .map(
            d =>
              DeployInfoPageTokenParams(
                d.getHeader.timestamp,
                d.deployHash,
                isNext = false
              )
          )
      )
      (nextPageToken, prevPageToken)
    }
}
