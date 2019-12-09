package io.casperlabs.casper.validation

import cats.Functor
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.InvalidBlock
import io.casperlabs.casper.PrettyPrinter
import io.casperlabs.shared.Log

import scala.concurrent.duration.FiniteDuration

object Errors {
  // Wrapper for the tests that were originally outside the `attemptAdd` method
  // and meant the block was not getting saved.
  final case class DropErrorWrapper(status: InvalidBlock)     extends Exception
  final case class ValidateErrorWrapper(status: InvalidBlock) extends Exception(status.toString)

  sealed trait DeployHeaderError { self =>
    val deployHash: ByteString
    def errorMessage: String

    def logged[F[_]: Log: Functor]: F[DeployHeaderError] =
      Log[F].warn(s"${self.errorMessage -> "error" -> null}").as(self)
  }
  object DeployHeaderError {
    def missingHeader(deployHash: ByteString): DeployHeaderError = MissingHeader(deployHash)

    def timeToLiveTooShort(
        deployHash: ByteString,
        ttl: Int,
        minTTL: FiniteDuration
    ): DeployHeaderError =
      TimeToLiveTooShort(deployHash, ttl, minTTL)

    def timeToLiveTooLong(deployHash: ByteString, ttl: Int, maxTTL: Int): DeployHeaderError =
      TimeToLiveTooLong(deployHash, ttl, maxTTL)

    def tooManyDependencies(
        deployHash: ByteString,
        numDependencies: Int,
        maxDependencies: Int
    ): DeployHeaderError =
      TooManyDependencies(deployHash, numDependencies, maxDependencies)

    def invalidDependency(deployHash: ByteString, dependency: ByteString): DeployHeaderError =
      InvalidDependency(deployHash, dependency)

    def invalidChainName(
        deployHash: ByteString,
        deployChainName: String,
        expectedChainName: String
    ): DeployHeaderError =
      InvalidChainName(deployHash, deployChainName, expectedChainName)

    def timestampInFuture(
        deployHash: ByteString,
        timestamp: Long,
        drift: Int
    ): DeployHeaderError =
      TimestampInFuture(deployHash, timestamp, drift)

    final case class MissingHeader(deployHash: ByteString) extends DeployHeaderError {
      def errorMessage: String =
        s"Deploy ${PrettyPrinter.buildString(deployHash)} does not contain a header"
    }

    final case class TimeToLiveTooShort(deployHash: ByteString, ttl: Int, minTTL: FiniteDuration)
        extends DeployHeaderError {
      def errorMessage: String =
        s"Time to live $ttl in deploy ${PrettyPrinter.buildString(deployHash)} is shorter than minimum valid time to live ${minTTL.toMillis}"
    }

    final case class TimeToLiveTooLong(deployHash: ByteString, ttl: Int, maxTTL: Int)
        extends DeployHeaderError {
      def errorMessage: String =
        s"Time to live $ttl in deploy ${PrettyPrinter.buildString(deployHash)} is longer than maximum valid time to live $maxTTL"
    }

    final case class TooManyDependencies(
        deployHash: ByteString,
        numDependencies: Int,
        maxDependencies: Int
    ) extends DeployHeaderError {
      def errorMessage: String =
        s"Deploy ${PrettyPrinter.buildString(deployHash)} with $numDependencies is invalid. The maximum number of dependencies is $maxDependencies"
    }

    final case class InvalidDependency(deployHash: ByteString, dependency: ByteString)
        extends DeployHeaderError {
      def errorMessage: String = {
        val deploy = PrettyPrinter.buildString(deployHash)
        val dep    = PrettyPrinter.buildString(dependency)

        s"Deploy $deploy with dependency $dep is invalid. Dependencies are expected to be 32 bytes."
      }
    }

    final case class InvalidChainName(
        deployHash: ByteString,
        deployChainName: String,
        expectedChainName: String
    ) extends DeployHeaderError {
      def errorMessage: String =
        s"Deploy ${PrettyPrinter.buildString(deployHash)} with chain name '$deployChainName' is invalid. Expected empty chain or '$expectedChainName'."
    }

    final case class TimestampInFuture(
        deployHash: ByteString,
        timestamp: Long,
        drift: Int
    ) extends DeployHeaderError {
      def errorMessage: String =
        s"Deploy ${PrettyPrinter.buildString(deployHash)} has timestamp $timestamp which is more than ${drift}ms in the future."
    }
  }
}
