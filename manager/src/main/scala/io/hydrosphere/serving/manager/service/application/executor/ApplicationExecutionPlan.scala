package io.hydrosphere.serving.manager.service.application.executor

import io.hydrosphere.serving.manager.model.db.{ApplicationExecutionGraph, DetailedServiceDescription}

sealed trait ApplicationExecutionPlan extends Product with Serializable

case class SimplePlan(
  key: String,
  service: DetailedServiceDescription
) extends ApplicationExecutionPlan

case class DAGPlan(
  graph: ApplicationExecutionGraph
) extends ApplicationExecutionPlan








