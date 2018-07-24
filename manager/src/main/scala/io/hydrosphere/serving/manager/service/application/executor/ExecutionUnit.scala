package io.hydrosphere.serving.manager.service.application.executor

case class ExecutionUnit(
  serviceName: String,
  servicePath: String,
  stageInfo: StageInfo,
)
