package io.casperlabs

package object catscontrib
    extends EitherTInstances
    with StateTInstances
    with ApplicativeError_Instances
    with MonadThrowableInstances {
  type Fs2Compiler[F[_]] = fs2.Stream.Compiler[F, F]
}
