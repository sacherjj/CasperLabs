package io.casperlabs.casper.helper

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.casper.util.ProtoUtil

class DeployBuilder(private val deploy: Deploy) {
  def build: Deploy = {
    val header = deploy.getHeader.withBodyHash(ProtoUtil.protoHash(deploy.getBody))
    deploy.withDeployHash(ProtoUtil.protoHash(header)).withHeader(header)
  }

  def withSessionCode(bytes: ByteString): DeployBuilder =
    new DeployBuilder(
      deploy.withBody(deploy.getBody.withSession(Deploy.Code().withWasm(bytes)))
    )
  def withPaymentCode(bytes: ByteString): DeployBuilder =
    new DeployBuilder(
      deploy.withBody(deploy.getBody.withPayment(Deploy.Code().withWasm(bytes)))
    )
  def withTimestamp(timestamp: Long): DeployBuilder =
    new DeployBuilder(
      deploy.withHeader(deploy.getHeader.withTimestamp(timestamp))
    )
  def withTtl(ttl: Int): DeployBuilder =
    new DeployBuilder(
      deploy.withHeader(deploy.getHeader.withTtlMillis(ttl))
    )
  def withDependencies(dependencies: Seq[ByteString]): DeployBuilder =
    new DeployBuilder(
      deploy.withHeader(deploy.getHeader.withDependencies(dependencies))
    )
}

object DeployBuilder {
  def apply(): DeployBuilder = new DeployBuilder(
    Deploy().withBody(
      Deploy
        .Body()
        .withSession(Deploy.Code().withWasm(ByteString.EMPTY))
        .withPayment(Deploy.Code().withWasm(ByteString.EMPTY))
    )
  )
}
