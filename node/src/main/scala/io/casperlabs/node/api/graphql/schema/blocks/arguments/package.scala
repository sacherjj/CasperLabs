package io.casperlabs.node.api.graphql.schema.blocks

import sangria.schema._

package object arguments {
  val BlockHashPrefix =
    Argument(
      "blockHashBase16Prefix",
      StringType,
      description = "Prefix or full base-16 hash of a block"
    )

  val Depth =
    Argument(
      "depth",
      IntType,
      description = "How many of the top ranks of the DAG to show"
    )

  val MaxRank =
    Argument(
      "maxRank",
      OptionInputType(LongType),
      "The maximum rank to to go back from, 0 means go from the current tip of the DAG",
      0L
    )

  val DeployHash =
    Argument(
      "deployHashBase16",
      StringType,
      description = "Base-16 hash of a deploy, must be 64 characters long"
    )
}
