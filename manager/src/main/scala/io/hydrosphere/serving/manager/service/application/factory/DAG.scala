package io.hydrosphere.serving.manager.service.application.factory

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

case class DAG[T](nodes: Seq[T], links: Seq[(T, T)]) {

  lazy val sources: Seq[T] = links.map(_._1)
  lazy val destinations: Seq[T] = links.map(_._2)

  lazy val roots: Seq[T] = {
    if (links.nonEmpty) {
      nodes.filterNot(destinations.contains)
    } else {
      nodes
    }
  }

  /**
    * returns true if graph has no cycles;
    *
    * @return Boolean
    */
  def isAcyclic(): Boolean = {
    var remainingNodes = roots.to[ListBuffer]
    var edges = links.to[ListBuffer]
    val resultNodes = new ListBuffer[T]()

    while (remainingNodes.nonEmpty) {
      val currentNode = remainingNodes.head
      remainingNodes -= currentNode
      resultNodes += currentNode
      val adjacentNodes = edges.filter(x => x._1 == currentNode)
      for (edge <- adjacentNodes) {
        val adjacentSource = edge._2
        edges -= edge
        if (!edges.exists(x => x._2 == adjacentSource)) {
          remainingNodes += nodes.filter(x => x == adjacentSource).head
        }
      }
    }
    edges.isEmpty
  }


  /**
    * Checks if there is only one connected component
    * https://en.wikipedia.org/wiki/Connected_component_(graph_theory)
    *
    * @return
    */
  def isSingleComponent(): Boolean = {
    val visitedNodes = mutable.ListBuffer.empty[T]

    def _visit_downstream(currentNode: T): Unit = {
      visitedNodes += currentNode
      val nextSourceNodes = links.filter(_._1 == currentNode).map(_._2)
      val nextDestNodes = links.filter(_._2 == currentNode).map(_._1)
      val nextNodes = nextSourceNodes ++ nextDestNodes
      nextNodes.filterNot(visitedNodes.contains).foreach(_visit_downstream)
    }

    roots.headOption.foreach(_visit_downstream)

    val notVisited = nodes.toSet -- visitedNodes.toSet
    notVisited.isEmpty
  }
}