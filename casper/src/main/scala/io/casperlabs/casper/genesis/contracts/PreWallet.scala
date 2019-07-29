package io.casperlabs.casper.genesis.contracts

import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import scala.util.{Failure, Success, Try}

case class PreWallet(publicKey: Keys.PublicKey, initBalance: BigInt)

object PreWallet {

  def fromLine(line: String): Either[String, PreWallet] =
    line.split(",").filter(_.nonEmpty) match {
      case Array(publicKeyBase64, initBalanceStr, _) =>
        for {
          initBalance <- Try(BigInt(initBalanceStr)).toOption match {
                          case Some(initBalance) =>
                            Right(initBalance)
                          case None =>
                            Left(s"Failed to parse given initial balance $initBalanceStr as int.")
                        }
          // TODO: To support Ethereum wallets we'll need to recognise them and add support for longer keys.
          publicKey <- Ed25519.tryParsePublicKey(publicKeyBase64) match {
                        case Some(key) =>
                          Right(key)
                        case None =>
                          Left(s"Failed to parse given public key: $publicKeyBase64")
                      }
        } yield PreWallet(publicKey, initBalance)

      case _ => Left(s"Invalid pre-wallet specification:\n$line")
    }
}
