package io.hydrosphere.serving.manager.service.application.factory

import io.hydrosphere.serving.manager.service.environment.EnvironmentManagementService
import io.hydrosphere.serving.manager.service.model_version.ModelVersionManagementService
import io.hydrosphere.serving.manager.service.runtime.RuntimeManagementService

case class FactoryParams (
  modelVersionManagementService: ModelVersionManagementService,
  runtimeManagementService: RuntimeManagementService,
  environmentManagementService: EnvironmentManagementService
)
