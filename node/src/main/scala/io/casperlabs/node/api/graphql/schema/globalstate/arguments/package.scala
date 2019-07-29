package io.casperlabs.node.api.graphql.schema.globalstate

import cats.syntax.option._
import com.github.ghik.silencer.silent
import sangria.marshalling.{CoercedScalaResultMarshaller, FromInput, ResultMarshaller}
import sangria.schema._

package object arguments {
  val GlobalStateKeyType = EnumType(
    "KeyType",
    "Type of key used to query the global state".some,
    List(
      EnumValue(
        "Hash",
        value = "Hash"
      ),
      EnumValue(
        "URef",
        value = "URef"
      ),
      EnumValue(
        "Address",
        value = "Address"
      )
    )
  )

  val KeyType = Argument(
    "keyType",
    GlobalStateKeyType,
    "Type of key used to query the global state"
  )

  val Key = Argument(
    "keyBase16",
    StringType,
    "Base-16 encoded key"
  )

  @silent("it is not recommended to define classes/objects inside of package objects")
  case class StateQuery(keyType: String, key: String, pathSegments: Seq[String])

  implicit val decoder: FromInput[StateQuery] = new FromInput[StateQuery] {
    override val marshaller: ResultMarshaller = CoercedScalaResultMarshaller.default

    override def fromResult(node: marshaller.Node): StateQuery = {
      val ad = node.asInstanceOf[Map[String, Any]]
      StateQuery(
        ad("keyType").asInstanceOf[String],
        ad("keyBase16").asInstanceOf[String],
        ad("pathSegments").asInstanceOf[Seq[String]]
      )
    }
  }

  val StateQueryType: InputObjectType[StateQuery] = InputObjectType[StateQuery](
    "StateQuery",
    List(
      InputField(
        "keyType",
        GlobalStateKeyType
      ),
      InputField(
        "keyBase16",
        StringType,
        "Must be 32 bytes long"
      ),
      InputField(
        "pathSegments",
        ListInputType(StringType)
      )
    )
  )

  val StateQueryArgument = Argument("StateQueries", ListInputType(StateQueryType))
}
