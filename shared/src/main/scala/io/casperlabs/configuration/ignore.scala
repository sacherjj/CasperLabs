package io.casperlabs.configuration
import scala.annotation.StaticAnnotation

/**
  * Ignores parameter during configuration parameters Magnolia parsing taking default value
  * TODO: add actual semantics
  */
class ignore extends StaticAnnotation
