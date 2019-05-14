package io.casperlabs.crypto.signatures

import java.io.StringReader

import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey, PublicKeyA, Signature}
import io.casperlabs.crypto.codec.Base64
import org.bouncycastle.openssl.PEMKeyPair

import scala.util.Random

/**
  * Useful links:
  * [[https://tls.mbed.org/kb/cryptography/asn1-key-structures-in-der-and-pem]]
  * [[https://wiki.openssl.org/index.php/Command_Line_Elliptic_Curve_Operations]]
  */
sealed trait SignatureAlgorithm {

  def name: String

  /**
    * Verifies the given signature.
    *
    * @param data      The data which was signed, must be exactly 32 bytes
    * @param signature The signature
    * @param pub       The public key which did the signing
    * @return Boolean value of verification
    */
  def verify(data: Array[Byte], signature: Signature, pub: PublicKeyA): Boolean

  def newKeyPair: (PrivateKey, PublicKeyA)

  /**
    * Create a signature.
    *
    * @param data Message hash, 32 bytes
    * @param sec  Secret key, 32 bytes
    * @return Byte array of signature
    */
  def sign(data: Array[Byte], sec: PrivateKey): Signature

  /**
    * Computes public key from secret key
    */
  def tryToPublic(privateKey: PrivateKey): Option[PublicKeyA]

  def tryParsePrivateKey(str: String): Option[PrivateKey]

  def tryParsePublicKey(str: String): Option[PublicKeyA]

  def areMatchTogether(publicKey: PublicKeyA, privateKey: PrivateKey): Boolean = {
    val a = Array.ofDim[Byte](32)
    Random.nextBytes(a)
    val signature = sign(a, privateKey)
    verify(a, signature, publicKey)
  }
}

object SignatureAlgorithm {

  /**
    * TODO: Temporarily disabled `secp256k1` because of errors,
    * because some parts of the node complain if validator ID's size
    * doesn't equal to 32 bytes
    */
  def unapply(alg: String): Option[SignatureAlgorithm] = alg match {
    case "ed25519" => Some(Ed25519)
    //    case "secp256k1" => Some(Secp256k1)
    case _ => None
  }

  /**
    * Short introduction [[https://ed25519.cr.yp.to]]
    */
  object Ed25519 extends SignatureAlgorithm {

    import io.casperlabs.crypto.codec.Base64
    import org.abstractj.kalium.keys.{SigningKey, VerifyKey}

    override def name: String = "ed25519"

    /**
      * Expects key to be in OpenSSL format or raw key encoded in base64.
      *
      * Example OpenSSL:
      * ```
      * -----BEGIN PRIVATE KEY-----
      * MC4CAQAwBQYDK2VwBCIEIElVumd7dKQmSsMHZeRFSbxwFZ59PFNJQ8VpiCFmwoY0
      * -----END PRIVATE KEY-----
      * ```
      *
      * Example base64: `cQyHB8q9DiLcmVkaYUne2OFpkosW26fMBCKH0rZvZe0=`
      */
    override def tryParsePrivateKey(str: String): Option[PrivateKey] = {
      val KeyLength = 32

      /**
        * Skips header `-----BEGIN PRIVATE KEY-----` and footer `-----END PRIVATE KEY-----`
        */
      def cleanIfPemFile(s: String) =
        str.split('\n').filterNot(_.contains("PRIVATE KEY")).mkString("")

      def tryParse(a: Array[Byte]): Option[Array[Byte]] = a.length match {
        // Some of ed25519 private keys are a concatenation of both private (on the left) and public (on the right).
        case x if x == KeyLength || x == KeyLength * 2 => Some(a.take(KeyLength))
        // OpenSSL generates private keys with additional information
        // at the beginning of keys which is not part of keys themselves.
        // We can safely ignore them.
        case x if x > KeyLength && x < KeyLength * 2 =>
          Some(a.drop(x % KeyLength))
        case _ =>
          None
      }

      val cleaned = cleanIfPemFile(str)
      for {
        decoded <- Base64.tryDecode(cleaned)
        parsed  <- tryParse(decoded)
      } yield PrivateKey(parsed)
    }

    /**
      * Expects key to be in OpenSSL format or raw encoded in base64.
      *
      * Example OpenSSL:
      * ```
      * -----BEGIN PUBLIC KEY-----
      * MCowBQYDK2VwAyEAhRAJx+krVtJQ3+jRzE5HMAheSn7YzzPVBDMgyJQdUq0=
      * -----END PUBLIC KEY-----
      * ```
      *
      * Example base64: `T81Noks9FR3Qj3mBLn/+Az9UG5bgTAc5yWhAQ6WpFn8=`
      */
    override def tryParsePublicKey(str: String): Option[PublicKeyA] = {
      val KeyLength = 32

      /**
        * Skips header `-----BEGIN PUBLIC KEY-----` and footer `-----END PUBLIC KEY-----`
        */
      def cleanIfPemFile(s: String) =
        str.split('\n').filterNot(_.contains("PUBLIC KEY")).mkString("")

      def tryParse(a: Array[Byte]): Option[Array[Byte]] = a.length match {
        // Some of ed25519 private keys are a concatenation of both private (on the left) and public (on the right).
        case x if x == KeyLength || x == KeyLength * 2 => Some(a.takeRight(KeyLength))
        // OpenSSL generates public keys with additional information
        // at the beginning of keys which is not part of keys themselves.
        // We can safely ignore them.
        case x if x > KeyLength && x < KeyLength * 2 =>
          Some(a.drop(x % KeyLength))
        case _ => None
      }

      val cleaned = cleanIfPemFile(str)
      for {
        decoded <- Base64.tryDecode(cleaned)
        parsed  <- tryParse(decoded)
      } yield PublicKey(parsed)
    }

    override def newKeyPair: (PrivateKey, PublicKeyA) = {
      val key = new SigningKey()
      val sec = key.toBytes
      val pub = key.getVerifyKey.toBytes
      (PrivateKey(sec), PublicKey(pub))
    }

    /**
      * Computes public key from secret key
      */
    override def tryToPublic(sec: PrivateKey): Option[PublicKeyA] =
      try {
        val key = new SigningKey(sec)
        Some(PublicKey(key.getVerifyKey.toBytes))
      } catch {
        case _: Throwable => None
      }

    /**
      * Verifies the given signature.
      *
      * @param data      The data which was signed, must be exactly 32 bytes
      * @param signature The signature
      * @param pub       The public key which did the signing
      * @return Boolean value of verification
      */
    override def verify(data: Array[Byte], signature: Signature, pub: PublicKeyA): Boolean =
      try {
        new VerifyKey(pub).verify(data, signature)
      } catch {
        case ex: RuntimeException =>
          if (ex.getMessage contains "signature was forged or corrupted") {
            false
          } else {
            throw ex
          }
      }

    /**
      * Create an ED25519 signature.
      *
      * @param data Message hash, 32 bytes
      * @param sec  Secret key, 32 bytes
      * @return Byte array of signature
      */
    override def sign(data: Array[Byte], sec: PrivateKey): Signature =
      Signature(new SigningKey(sec).sign(data))
  }

  object Secp256k1 extends SignatureAlgorithm {

    import java.security.KeyPairGenerator
    import java.security.interfaces.ECPrivateKey
    import java.security.spec.ECGenParameterSpec

    import com.google.common.base.Strings
    import io.casperlabs.crypto.codec.Base16
    import io.casperlabs.crypto.util.SecureRandomUtil
    import org.bitcoin._
    import org.bouncycastle.jce.provider.BouncyCastleProvider

    private val PrivateKeyLength = 32
    private val PublicKeyLength  = 65
    private val provider         = new BouncyCastleProvider()
    private val curveName        = "secp256k1"

    override def name: String = curveName

    /**
      * Expects the key to be in PEM format without parameters section or raw key encoded in base64.
      *
      * Example of PEM:
      * ```
      * -----BEGIN EC PRIVATE KEY-----
      * MHQCAQEEIFS9fBey9dtXX+EsNWJsNS6+I30bZuT1lBUiP9bYTo9PoAcGBSuBBAAK
      * oUQDQgAEj1fgdbpNbt06EY/8C+wbBXq6VvG+vCVDNl74LvVAmXfpdzCWFKbdrnIl
      * X3EFDxkd9qpk35F/kLcqV3rDn/u3dg==
      * -----END EC PRIVATE KEY-----
      * ```
      *
      * Example of base64: `IRhZtFy4Ku1BLUJ7kIyU54DhdtZq4xt5yjUR4mwtvYY=`
      */
    override def tryParsePrivateKey(str: String): Option[PrivateKey] =
      try {
        Base64
          .tryDecode(str.trim)
          .collectFirst {
            case a if a.length == PrivateKeyLength => PrivateKey(a)
          }
          .orElse {
            import scala.collection.JavaConverters._

            val parser  = new org.bouncycastle.openssl.PEMParser(new StringReader(str.trim))
            val pemPair = parser.readObject().asInstanceOf[PEMKeyPair]
            pemPair.getPrivateKeyInfo
              .parsePrivateKey()
              .asInstanceOf[org.bouncycastle.asn1.DLSequence]
              .iterator()
              .asScala
              .collectFirst {
                case octet: org.bouncycastle.asn1.DEROctetString => PrivateKey(octet.getOctets)
              }
          }
      } catch {
        case _: Throwable =>
          None
      }

    /**
      * Expects key to be in PEM format or raw key encoded in base64.
      *
      * Example PEM:
      * ```
      * -----BEGIN PUBLIC KEY-----
      * MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEj1fgdbpNbt06EY/8C+wbBXq6VvG+vCVD
      * Nl74LvVAmXfpdzCWFKbdrnIlX3EFDxkd9qpk35F/kLcqV3rDn/u3dg==
      * -----END PUBLIC KEY-----
      * ```
      *
      * Example base64: `BFK6dV60mW8hUxjhwkNq4bG5OX/6kr6SXS1pi1zH2BQVERpfIw9QELDvK6Vc7pDaUhEM0M+OwVo7AxJyqr5dXOo=`
      */
    override def tryParsePublicKey(str: String): Option[PublicKeyA] =
      try {
        Base64
          .tryDecode(str.trim)
          .collectFirst {
            case a if a.length == PublicKeyLength => PublicKey(a)
          }
          .orElse {
            Some(
              PublicKey(
                new org.bouncycastle.openssl.PEMParser(new StringReader(str.trim))
                  .readObject()
                  .asInstanceOf[org.bouncycastle.asn1.x509.SubjectPublicKeyInfo]
                  .getPublicKeyData
                  .getBytes
              )
            )
          }
      } catch {
        case _: Throwable =>
          None
      }

    override def newKeyPair: (PrivateKey, PublicKeyA) = {
      val kpg = KeyPairGenerator.getInstance("ECDSA", provider)
      kpg.initialize(new ECGenParameterSpec(curveName), SecureRandomUtil.secureRandomNonBlocking)
      val kp = kpg.generateKeyPair

      val padded =
        Strings.padStart(kp.getPrivate.asInstanceOf[ECPrivateKey].getS.toString(16), 64, '0')
      val sec = PrivateKey(Base16.decode(padded))
      val pub = Secp256k1.tryToPublic(sec)

      (PrivateKey(sec), PublicKey(pub.get))
    }

    /**
      * Verifies the given signature.
      *
      * @param data      The data which was signed, must be exactly 32 bytes
      * @param signature The signature
      * @param pub       The public key which did the signing
      * @return Boolean value of verification
      */
    def verify(data: Array[Byte], signature: Signature, pub: PublicKeyA): Boolean =
      NativeSecp256k1.verify(data, signature, pub)

    /**
      * libsecp256k1 Create an ECDSA signature.
      *
      * @param data Message hash, 32 bytes
      * @param sec  Secret key, 32 bytes
      * @return Byte array of signature
      */
    override def sign(data: Array[Byte], sec: PrivateKey): Signature =
      Signature(NativeSecp256k1.sign(data, sec))

    /**
      * libsecp256k1 Seckey Verify - returns true if valid, false if invalid
      *
      * Input value
      *
      * @param seckey ECDSA Secret key, 32 bytes
      *
      *               Return value
      *               Boolean of secret key verification
      */
    def secKeyVerify(seckey: Array[Byte]): Boolean =
      NativeSecp256k1.secKeyVerify(seckey)

    /**
      * libsecp256k1 Compute Pubkey - computes public key from secret key
      *
      * @param seckey ECDSA Secret key, 32 bytes
      */
    def tryToPublic(seckey: PrivateKey): Option[PublicKeyA] =
      try {
        Some(PublicKey(NativeSecp256k1.computePubkey(seckey)))
      } catch {
        case _: Throwable => None
      }
  }

}
