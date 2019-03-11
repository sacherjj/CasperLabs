package io.casperlabs.configuration
import scala.annotation.StaticAnnotation

/**
  * Marks a field that will be redefined as a server.dataDir child at Magnolia post-processing
  * TODO: add actual semantics
  * @param relativePath child path to server.dataDir
  */
class relativeToDataDir(val relativePath: String) extends StaticAnnotation
