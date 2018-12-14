package io.casperlabs.models.testUtils
import io.casperlabs.models.Par
import io.casperlabs.models.rholang.sorter.Sortable
import monix.eval.Coeval

object TestUtils {
  def sort(par: Par): Par = Sortable[Par].sortMatch[Coeval](par).map(_.term).value()
}
