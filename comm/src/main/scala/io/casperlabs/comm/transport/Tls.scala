package io.casperlabs.comm.transport

import java.nio.file.Path

import io.casperlabs.configuration.{ignore, SubConfig}

final case class Tls(
    certificate: Path,
    key: Path,
    @ignore
    customCertificateLocation: Boolean = false,
    @ignore
    customKeyLocation: Boolean = false,
    secureRandomNonBlocking: Boolean
) extends SubConfig
