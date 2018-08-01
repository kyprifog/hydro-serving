package io.hydrosphere.serving.manager.service.application.factory

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
}

object DAG {
  case class Link[T](source: T, destination: T)
}