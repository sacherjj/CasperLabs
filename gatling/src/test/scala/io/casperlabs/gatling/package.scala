package io.casperlabs

import com.google.protobuf.ByteString
import io.casperlabs.comm.discovery.{Node, Ping}
import io.gatling.commons.validation
import io.gatling.core.session.Expression

import scala.util.Random

package object gatling {
  def getRandomHexString(numchars: Int) = {
    val sb = new StringBuffer
    while (sb.length < numchars)
      sb.append(Integer.toHexString(Random.nextInt))

    sb.toString.substring(0, numchars)
  }

  implicit class StringOps1(str: String) {
    def toByteStr = ByteString.copyFromUtf8(str)
  }

  def randomNode: Node = {
    Node(
      id = getRandomHexString(20).toByteStr,
      host = "127.0.0.1",
      protocolPort = 1337,
      discoveryPort = 1337
    )
  }

  def buildValidExpr[A](a: => A): Expression[A] = _ => validation.Success(a)
}
