package io.hydrosphere.serving.manager.service.application.executor

import io.hydrosphere.serving.manager.model.db.Application

case class Executable[T <: ApplicationExecutionPlan](
  application: Application,
  plan: T
)
