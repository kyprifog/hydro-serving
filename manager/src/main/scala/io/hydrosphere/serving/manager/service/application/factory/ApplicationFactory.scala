package io.hydrosphere.serving.manager.service.application.factory

import io.hydrosphere.serving.manager.model.{HFResult, HResult, Result}
import io.hydrosphere.serving.manager.model.db.Application
import io.hydrosphere.serving.manager.service.application.CreateApplicationRequest
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.ExecutionContext

trait ApplicationFactory {
  def factoryParams: FactoryParams

  def create(req: CreateApplicationRequest): HFResult[Application]
}

object ApplicationFactory extends Logging {
  def forRequest(
    req: CreateApplicationRequest,
    factoryParams: FactoryParams
  )(implicit executionContext: ExecutionContext): HResult[ApplicationFactory] = {
    req.executionGraph.stages match {
      case singleStage :: Nil if singleStage.services.lengthCompare(1) == 0 =>
        Result.ok(new StandaloneApplicationFactory(factoryParams))
      case stages if req.executionGraph.stages.nonEmpty =>
        Result.ok(new DAGApplicationFactory(factoryParams))
    }
  }
}
