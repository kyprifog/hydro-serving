package io.hydrosphere.serving.manager.service.application.factory
import io.hydrosphere.serving.manager.model.HFResult
import io.hydrosphere.serving.manager.model.db.Application
import io.hydrosphere.serving.manager.service.application.CreateApplicationRequest

class DAGApplicationFactory(val factoryParams: FactoryParams) extends ApplicationFactory {
  override def create(req: CreateApplicationRequest): HFResult[Application] = ???
}
