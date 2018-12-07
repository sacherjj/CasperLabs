package coop.rchain.casper.util.comm

import scala.language.higherKinds
import scala.util.{Either, Right}

import cats.{Id, MonadError}
import cats.effect.Sync
import cats.instances.list._
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.monad._
import cats.syntax.functor._
import cats.syntax.traverse._

import coop.rchain.casper.util.rholang.InterpreterUtil
import coop.rchain.casper.protocol.Par
import coop.rchain.shared.Time

import com.google.protobuf.ByteString

object ListenAtName {
  sealed trait Name
  final case class PrivName(content: String) extends Name
  final case class PubName(content: String)  extends Name

  trait BuildPar[F[_]] {
    def build(f: F[Name]): F[Par]
  }

	// todo(abner) make sure will we provide this function to client
  def listenAtHashUntilChanged[A, G[_], F[_]: Sync: Time](
      name: G[Name]
  )(request: G[Par] => F[Seq[A]])(implicit par: BuildPar[λ[A => F[G[A]]]]): F[Unit] = ???

}
