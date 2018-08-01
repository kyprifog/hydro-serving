package io.hydrosphere.serving.manager.util

import java.util.UUID
import io.hydrosphere.serving.manager.GenericUnitTest
import io.hydrosphere.serving.manager.service.application.factory.DAG

class DAGSpec extends GenericUnitTest {
  describe("DAG") {
    describe("isAcyclic()") {
      val node1 = UUID.randomUUID()
      val node2 = UUID.randomUUID()
      val node3 = UUID.randomUUID()
      val node4 = UUID.randomUUID()
      val nodes = Seq(node1, node2, node3, node4)

      it("true for 1st graph without cycles") {
        val edges = Seq(
          node1 -> node2,
          node2 -> node4,
          node3 -> node4,
          node3 -> node2,
          node1 -> node3
        )
        val graph = DAG(nodes, edges)
        assert(graph.isAcyclic())
      }

      it("true for 2nd graph without cycles") {
        val edges = Seq(
          node2 -> node1
        )
        val graph = DAG(nodes, edges)
        assert(graph.isAcyclic())
      }

      it("true for 3rd graph without cycles") {
        val edges = Seq(
          node1 -> node3,
          node2 -> node1,
          node2 -> node4,
          node4 -> node3
        )
        val graph = DAG(nodes, edges)
        assert(graph.isAcyclic())
      }

      it("false for 1st graph with cycles") {
        val edges = Seq(
          node1 -> node2,
          node2 -> node3,
          node3 -> node4,
          node4 -> node2
        )
        val graph = DAG(nodes, edges)
        assert(!graph.isAcyclic())
      }

      it("false for 2nd graph with cycles") {
        val node5 = UUID.randomUUID()
        val nodes = Seq(node1, node2, node3, node4, node5)
        val edges = Seq(
          node1 -> node2,
          node2 -> node3,
          node2 -> node4,
          node3 -> node4,
          node4 -> node5,
          node3 -> node5,
          node5 -> node1
        )
        val graph = DAG(nodes, edges)
        assert(!graph.isAcyclic())
      }

      it("test for isolated points") {
        val node5 = UUID.randomUUID()
        val nodes = Seq(node1, node2, node3, node4, node5)
        val edges = Seq(
          node1 -> node2,
          node2 -> node4,
          node3 -> node4,
          node3 -> node2,
          node1 -> node3
        )
        val graph = DAG(nodes, edges)
        assert(!graph.isAcyclic())
      }
    }
  }
}