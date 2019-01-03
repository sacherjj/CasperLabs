package io.casperlabs.node.configuration

import java.nio.file.Path

import io.casperlabs.comm.PeerNode
import io.casperlabs.shared.StoreType

case class Server(
    host: Option[String],
    port: Int,
    httpPort: Int,
    kademliaPort: Int,
    dynamicHostAddress: Boolean,
    noUpnp: Boolean,
    defaultTimeout: Int,
    bootstrap: PeerNode,
    standalone: Boolean,
    genesisValidator: Boolean,
    dataDir: Path,
    mapSize: Long,
    storeType: StoreType,
    maxNumOfConnections: Int,
    maxMessageSize: Int
)

case class GrpcServer(
    host: String,
    socket: String,
    portExternal: Int,
    portInternal: Int
)

case class Tls(
    certificate: Path,
    key: Path,
    customCertificateLocation: Boolean,
    customKeyLocation: Boolean,
    secureRandomNonBlocking: Boolean
)

sealed trait Command
case object Diagnostics extends Command
case class Deploy(
    address: String,
    gasLimit: Long,
    gasPrice: Long,
    nonce: Long,
    sessionCodeLocation: String,
    paymentCodeLocation: String
) extends Command
case object DeployDemo             extends Command
case object Propose                extends Command
case class ShowBlock(hash: String) extends Command
case class ShowBlocks(depth: Int)  extends Command
case object Run                    extends Command
case object Help                   extends Command
case class BondingDeployGen(
    bondKey: String,
    ethAddress: String,
    amount: Long,
    secKey: String,
    pubKey: String
) extends Command
case class FaucetBondingDeployGen(
    amount: Long,
    sigAlgorithm: String,
    secKey: String,
    pubKey: String
) extends Command
