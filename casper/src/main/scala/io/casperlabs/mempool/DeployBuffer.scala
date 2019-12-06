package io.casperlabs.mempool

import cats.implicits._
import io.casperlabs.casper.PrettyPrinter
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.casper.validation.Validation
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.storage.deploy.{DeployStorage, DeployStorageWriter}

import scala.concurrent.duration.FiniteDuration
import io.casperlabs.shared.Log

trait DeployBuffer[F[_]] {

  /** If a deploy is valid (according to node rules), adds is the deploy buffer.
    * Otherwise returns an error.
    */
  def deploy(d: Deploy): F[Either[Throwable, Unit]]
}

object DeployBuffer {
  def create[F[_]: MonadThrowable: DeployStorage: Log](
      chainName: String,
      minTtl: FiniteDuration
  ): DeployBuffer[F] =
    new DeployBuffer[F] {
      private def validateDeploy(deploy: Deploy): F[Unit] = {
        def illegal(msg: String): F[Unit] =
          MonadThrowable[F].raiseError(new IllegalArgumentException(msg))

        def check(msg: String)(f: F[Boolean]): F[Unit] =
          f flatMap { ok =>
            illegal(msg).whenA(!ok)
          }

        for {
          _ <- (deploy.getBody.session, deploy.getBody.payment) match {
                case (None, _) | (_, None) |
                    (Some(Deploy.Code(_, _, Deploy.Code.Contract.Empty)), _) |
                    (_, Some(Deploy.Code(_, _, Deploy.Code.Contract.Empty))) =>
                  illegal(s"Deploy was missing session and/or payment code.")
                case _ => ().pure[F]
              }
          _ <- check("Invalid deploy hash.")(Validation.deployHash[F](deploy))
          _ <- check("Invalid deploy signature.")(Validation.deploySignature[F](deploy))
          _ <- check("Invalid chain name.")(
                Validation.validateChainName[F](deploy, chainName).map(_.isEmpty)
              )
          _ <- check(
                s"Invalid deploy TTL. Deploy TTL: ${deploy.getHeader.ttlMillis} ms, minimum TTL: ${minTtl.toMillis}."
              )(Validation.minTtl[F](deploy, minTtl).map(_.isEmpty))
        } yield ()
      }

      override def deploy(d: Deploy): F[Either[Throwable, Unit]] =
        (for {
          _ <- validateDeploy(d)
          _ <- DeployStorageWriter[F].addAsPending(List(d))
          _ <- Log[F].info(s"Received ${PrettyPrinter.buildString(d) -> "deploy" -> null}")
        } yield ()).attempt
    }
}
