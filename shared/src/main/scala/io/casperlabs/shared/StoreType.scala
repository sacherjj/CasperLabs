package io.casperlabs.shared

sealed trait StoreType
object StoreType {
  case object Mixed extends StoreType {
    override def toString: String = "mixed"
  }
  case object LMDB extends StoreType {
    override def toString: String = "lmdb"
  }
  case object InMem extends StoreType {
    override def toString: String = "inmem"
  }

  def from(s: String): Option[StoreType] = s match {
    case "mixed" => Some(Mixed)
    case "inmem" => Some(InMem)
    case "lmdb"  => Some(LMDB)
    case _       => None
  }
}
