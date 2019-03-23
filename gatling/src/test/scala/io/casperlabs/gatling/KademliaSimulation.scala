package io.casperlabs.gatling

import com.github.phisgr.gatling.grpc.Predef._
import io.casperlabs.comm.discovery.{KademliaRPCServiceGrpc, Lookup, Ping}
import io.gatling.core.Predef._
import io.gatling.core.session.Expression
import io.grpc.ManagedChannelBuilder

import scala.util.Random

class KademliaSimulation extends Simulation {
  val grpcConf = grpc(ManagedChannelBuilder.forAddress("localhost", 40404).usePlaintext())
    .shareChannel

  val numberOfKnownNodes = 200

  val knownNodes = Array.fill(numberOfKnownNodes)(randomNode)

  val knownNodeProbability = 0.75

  def randomKnownNode =
    knownNodes(Random.nextInt(knownNodes.length))

  def knownOrRandomNode =
    if (Random.nextDouble() < knownNodeProbability)
      randomKnownNode
    else
      randomNode

  def randomPing: Expression[Ping] =
    buildValidExpr(Ping(Some(randomNode)))

  def lookupNode: Expression[Lookup] = buildValidExpr {
    Lookup(knownOrRandomNode.id, Some(knownOrRandomNode))
  }

  def pingKnown: Expression[Ping] =
    buildValidExpr(Ping(Some(randomKnownNode)))


  val pingSc = scenario("Kademlia ping")
      .repeat(1000) {
        exec(
          grpc("kademlia-ping-flood")
            .rpc(KademliaRPCServiceGrpc.METHOD_SEND_PING)
            .payload(randomPing)
        ).exitHereIfFailed
      }
      .repeat(200) {
        exec(
          grpc("kademlia-ping-known")
            .rpc(KademliaRPCServiceGrpc.METHOD_SEND_PING)
            .payload(pingKnown)
        )
      }

  val lookupSc = scenario("Kademlia lookup")
    .repeat(1000) {
      exec(
        grpc("kademlia-lookup")
          .rpc(KademliaRPCServiceGrpc.METHOD_SEND_LOOKUP)
          .payload(lookupNode)
      )
    }

  setUp(
    pingSc.inject(atOnceUsers(20)),
    lookupSc.inject(atOnceUsers(20))
  ).protocols(grpcConf)
}
