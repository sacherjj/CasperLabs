package io.casperlabs.casper.util

import io.casperlabs.casper.Estimator.BlockHash

import scala.collection.immutable.{HashMap, HashSet}

final class DoublyLinkedDag[A](
    val parentToChildAdjacencyList: Map[A, Set[A]],
    val childToParentAdjacencyList: Map[A, Set[A]],
    val dependencyFree: Set[A]
) {
  def add(parent: A, child: A): DoublyLinkedDag[A] = {
    val updatedParentToChildAdjacencyList =
      MapHelper.updatedWith[A, Set[A]](parentToChildAdjacencyList, parent)(Set(child))(_ + child)
    val updatedChildToParentAdjacencyList =
      MapHelper.updatedWith[A, Set[A]](childToParentAdjacencyList, child)(Set(parent))(_ + parent)

    val postParentDependencyFree =
      if (updatedChildToParentAdjacencyList.get(parent).exists(_.nonEmpty)) {
        dependencyFree
      } else {
        dependencyFree + parent
      }

    val postChildDependencyFree = postParentDependencyFree - child

    new DoublyLinkedDag[A](
      updatedParentToChildAdjacencyList,
      updatedChildToParentAdjacencyList,
      postChildDependencyFree
    )
  }

  // If the element doesn't exist in the dag, the dag is returned as is
  def remove(element: A): DoublyLinkedDag[A] = {
    assert(!childToParentAdjacencyList.contains(element))
    assert(!parentToChildAdjacencyList.values.toSet.contains(element))
    val maybeChildren = parentToChildAdjacencyList.get(element)
    val initAcc       = (childToParentAdjacencyList, Set.empty[A])
    val (updatedChildToParentAdjacencyList, newDependencyFree) = maybeChildren match {
      case Some(children) =>
        children.foldLeft(initAcc) {
          case ((childToParentAdjacencyListAcc, dependencyFreeAcc), child) =>
            val maybeParents = childToParentAdjacencyListAcc.get(child)
            maybeParents match {
              case Some(parents) =>
                val updatedParents = parents - element
                if (updatedParents.isEmpty) {
                  (childToParentAdjacencyListAcc - child, dependencyFreeAcc + child)
                } else {
                  (childToParentAdjacencyListAcc.updated(child, updatedParents), dependencyFreeAcc)
                }
              case None =>
                throw new Error(s"We should have at least $element as parent")
            }
        }
      case None => initAcc
    }
    new DoublyLinkedDag[A](
      this.parentToChildAdjacencyList - element,
      updatedChildToParentAdjacencyList,
      this.dependencyFree ++ newDependencyFree - element
    )
  }
}

object BlockDependencyDag {
  type BlockDependencyDag = DoublyLinkedDag[BlockHash]

  def empty: BlockDependencyDag =
    new BlockDependencyDag(
      HashMap.empty[BlockHash, Set[BlockHash]],
      HashMap.empty[BlockHash, Set[BlockHash]],
      HashSet.empty[BlockHash]
    )
}

object MapHelper {
  def updatedWith[A, B](map: Map[A, B], key: A)(default: B)(f: B => B): Map[A, B] = {
    val newValue = map.get(key).fold(default)(f)
    map.updated(key, newValue)
  }
}
