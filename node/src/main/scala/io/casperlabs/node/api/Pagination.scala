package io.casperlabs.node.api

import java.util

import com.google.protobuf.ByteString
import io.casperlabs.casper.api.BlockAPI.BlockAndMaybeDeploys
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.comm.ServiceError.InvalidArgument
import pbdirect._
import io.casperlabs.node.{ByteStringReader, ByteStringWriter}
import scala.util.{Failure, Success, Try}

trait Pagination {
  type PageTokenParams

  type PageSize  = Int
  type PageToken = String

  def parsePageToken(
      pageSize: PageSize,
      pageToken: PageToken
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
      size: PageSize,
      token: PageToken
  ): Try[(PageSize, PageTokenParams)] = {
    val pageSize = math.max(0, math.min(size, MAXSIZE))
    if (token.isEmpty) {
      Try { (pageSize, DeployInfoPageTokenParams(Long.MaxValue, ByteString.EMPTY, isNext = true)) }
    } else
      Try {
        util.Base64.getUrlDecoder
          .decode(token.trim)
          .pbTo[DeployInfoPageTokenParams]
      } match {
        case Failure(_) =>
          Failure(
            InvalidArgument(
              "Failed parsing pageToken"
            )
          )
        case Success(pageTokenParams) =>
          Success {
            (pageSize, pageTokenParams)
          }
      }
  }

  override def createPageToken(
      pageTokenParamsOpt: Option[PageTokenParams]
  ): PageToken =
    pageTokenParamsOpt match {
      case None => ""
      case Some(pageTokenParams) =>
        util.Base64.getUrlEncoder.encodeToString(pageTokenParams.toPB)
    }

  /**
    * Compute the nextPageToken and prevPageToken.
    *
    * If `deploys` is not empty,
    *   then the `nextPageToken` can be generated from the last element of `deploys`,
    *     and the `prevPageToken` can generate from the first element of `deploys`.
    *
    * If `deploys` is empty and we are fetching the next page,
    *   then the `prevPageToken` should be the MAX_CURSOR, and nextPageToken is "",
    *     to indicate there is no more elements.
    * Else if we are fetching the previous page,
    *   then the prevPageToken should be "",
    *     and nextPageToken should be the MIN_CURSOR.
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

/** TODO: common code can be abstracted from together with the [[DeployInfoPagination]].
  * However and most probably pagination will exist only for deploys and blocks.
  * Abstract only when a new pagination needs to be added. */
object BlockInfoPagination extends Pagination {
  case class BlockInfoPageTokenParams(
      lastTimeStamp: Long,
      lastBlockHash: ByteString
  )
  val MAXSIZE   = 25
  val NEXT_PAGE = "N"
  val PREV_PAGE = "P"

  override type PageTokenParams = BlockInfoPageTokenParams

  override def parsePageToken(
      size: PageSize,
      token: PageToken
  ): Try[(PageSize, PageTokenParams)] = {
    val pageSize = math.max(0, math.min(size, MAXSIZE))
    if (token.isEmpty) {
      Try { (pageSize, BlockInfoPageTokenParams(Long.MaxValue, ByteString.EMPTY)) }
    } else
      Try {
        util.Base64.getUrlDecoder
          .decode(token.trim)
          .pbTo[BlockInfoPageTokenParams]
      } match {
        case Failure(_) =>
          Failure(
            InvalidArgument(
              "Failed parsing pageToken"
            )
          )
        case Success(pageTokenParams) =>
          Success {
            (pageSize, pageTokenParams)
          }
      }
  }

  override def createPageToken(
      pageTokenParamsOpt: Option[PageTokenParams]
  ): PageToken =
    pageTokenParamsOpt match {
      case None => ""
      case Some(pageTokenParams) =>
        util.Base64.getUrlEncoder.encodeToString(pageTokenParams.toPB)
    }
}
