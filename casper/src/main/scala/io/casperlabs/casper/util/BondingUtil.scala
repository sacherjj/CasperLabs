package io.casperlabs.casper.util

import java.io.PrintWriter

import cats.effect.{Resource, Sync}
import cats.implicits._
import com.github.ghik.silencer.silent
import io.casperlabs.casper.consensus, consensus.state
import com.google.protobuf.ByteString

object BondingUtil {
  @silent("is never used")
  def bondingForwarderDeploy(bondKey: String, ethAddress: String): String = """"""

  @silent("is never used")
  def faucetBondDeploy[F[_]: Sync](
      amount: Long,
      sigAlgorithm: String,
      pubKey: String,
      secKey: Array[Byte]
  ): F[String] = """""".pure[F]

  def writeFile[F[_]: Sync](name: String, content: String): F[Unit] = {
    val file =
      Resource.make[F, PrintWriter](Sync[F].delay { new PrintWriter(name) })(
        pw => Sync[F].delay { pw.close() }
      )
    file.use(pw => Sync[F].delay { pw.println(content) })
  }

  type Bond = consensus.Bond

  object Bond {
    def apply(validator: ByteString, stake: Int) =
      consensus.Bond(validator).withStake(state.BigInt(stake.toString, 512))

    def unapply(bond: consensus.Bond) =
      consensus.Bond.unapply(bond)
  }

}
