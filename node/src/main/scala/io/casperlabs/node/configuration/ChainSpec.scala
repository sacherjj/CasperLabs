package io.casperlabs.node.configuration

import cats._
import cats.syntax._
import cats.implicits._
import cats.data.{ValidatedNel}
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric._
import io.casperlabs.configuration.SubConfig
import java.nio.file.Path
import scala.io.Source

/**
  * ChainSpec is the definition of the chain which we use for the Genesis process.
  * https://casperlabs.atlassian.net/wiki/spaces/EN/pages/133529693/Genesis+process+design+doc
  */
object ChainSpec extends ParserImplicits {

  /** The first set of changes should define the Genesis section and the costs. */
  final case class InitialConf(
      genesis: GenesisConf,
      wasmCosts: WasmCosts
  )

  object InitialConf {

    /** Parse the manifest. */
    def parse(manifest: => Source): ValidatedNel[String, InitialConf] =
      Utils.readFile(manifest).toValidatedNel[String].andThen { raw =>
        ConfParser
          .gen[InitialConf]
          .parse(
            cliByName = _ => None,
            envVars = Map.empty,
            configFile = None,
            defaultConfigFile = Utils.parseToml(raw),
            pathToField = Nil
          )
      }
  }

  final case class GenesisConf(
      name: String,
      timestamp: Long,
      mintCodePath: Path,
      posCodePath: Path,
      initialAccountsPath: Path,
      // TODO: Change this later to semver.
      protocolVersion: Long
  ) extends SubConfig

  final case class WasmCosts(
      regular: Int Refined NonNegative,
      divMultiplier: Int Refined NonNegative,
      mulMultiplier: Int Refined NonNegative,
      memMultiplier: Int Refined NonNegative,
      memInitialPages: Int Refined NonNegative,
      memGrowPerPage: Int Refined NonNegative,
      memCopyPerByte: Int Refined NonNegative,
      maxStackHeight: Int Refined NonNegative,
      opcodesMultiplier: Int Refined NonNegative,
      opcodesDivisor: Int Refined NonNegative
  ) extends SubConfig
}
