package io.casperlabs.comm

import cats.effect.Sync
import io.casperlabs.shared.Log

import scala.language.higherKinds

package object transport {

  def generateCertificateIfAbsent[F[_]: Log: Sync] =
    new GenerateCertificateIfAbsent[F]
}
