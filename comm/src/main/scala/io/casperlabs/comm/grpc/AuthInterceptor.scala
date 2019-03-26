package io.casperlabs.comm.grpc

import cats.Id
import com.google.protobuf.ByteString
import io.casperlabs.comm.auth.Principal
import io.casperlabs.comm.ServiceError.Unauthenticated
import io.casperlabs.crypto.util.CertificateHelper
import io.casperlabs.shared.{Log, LogSource}
import io.grpc.{
  Context,
  Contexts,
  Grpc,
  Metadata,
  ServerCall,
  ServerCallHandler,
  ServerInterceptor,
  Status
}
import javax.net.ssl.SSLSession
import scala.util.Try

/** Put the remote peer identity into the gRPC Context. */
class AuthInterceptor() extends ServerInterceptor {

  // Based on https://github.com/saturnism/grpc-java-by-example/tree/master/metadata-context-example/src/main/java/com/example/grpc/server

  implicit val logSource = LogSource(this.getClass)
  implicit val logId     = Log.logId

  def NoopListener[A] = new ServerCall.Listener[A] {}

  override def interceptCall[A, B](
      call: ServerCall[A, B],
      headers: Metadata,
      next: ServerCallHandler[A, B]
  ): ServerCall.Listener[A] = {

    val sslSessionOpt: Option[SSLSession] = Option(
      call.getAttributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION)
    )

    val ctx: Either[io.grpc.StatusRuntimeException, Context] =
      for {
        session <- sslSessionOpt orReject "No TLS session."
        cert <- Try(session.getPeerCertificates).toOption
                 .flatMap(_.headOption) orReject "No client certificate."
        id <- CertificateHelper.publicAddress(cert.getPublicKey) orReject "Certificate validation failed."
      } yield {
        val p = Principal.Peer(id = ByteString.copyFrom(id))
        Context.current.withValue(ContextKeys.Principal, p: Principal)
      }

    ctx.fold(
      ex => {
        Log[Id].warn(
          s"Rejecting gRPC call with ${ex.getStatus.getCode}: ${ex.getStatus.getDescription}"
        )
        call.close(ex.getStatus, headers)
        NoopListener
      },
      ctx => Contexts.interceptCall(ctx, call, headers, next)
    )
  }

  implicit class RejectOps[T](x: Option[T]) {
    def orReject(msg: String): Either[io.grpc.StatusRuntimeException, T] =
      x.map(Right(_)).getOrElse(Left(Unauthenticated(msg)))
  }
}
