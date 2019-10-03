package io.casperlabs.casper.helper

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.casper.util.ProtoUtil

object DeployOps {
  implicit class ChangeDeployOps(deploy: Deploy) {
    def withSessionCode(bytes: ByteString): Deploy =
      rehash(
        deploy.withBody(deploy.getBody.withSession(Deploy.Code().withWasm(bytes)))
      )
    def withPaymentCode(bytes: ByteString): Deploy =
      rehash(
        deploy.withBody(deploy.getBody.withPayment(Deploy.Code().withWasm(bytes)))
      )
    def withTimestamp(timestamp: Long): Deploy =
      rehash(
        deploy.withHeader(deploy.getHeader.withTimestamp(timestamp))
      )
    def withTtl(ttl: Int): Deploy =
      rehash(
        deploy.withHeader(deploy.getHeader.withTtlMillis(ttl))
      )
    def withDependencies(dependencies: Seq[ByteString]): Deploy =
      rehash(
        deploy.withHeader(deploy.getHeader.withDependencies(dependencies))
      )
  }

  private def rehash(deploy: Deploy): Deploy = {
    val header = deploy.getHeader.withBodyHash(ProtoUtil.protoHash(deploy.getBody))
    deploy.withDeployHash(ProtoUtil.protoHash(header)).withHeader(header)
  }
}
