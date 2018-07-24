package io.hydrosphere.serving.manager.model.db

case class ApplicationExecutionGraph(
  stages: Seq[ApplicationStage],
  links: Seq[StageLink]
)

case class StageLink(from: ApplicationStage, to: ApplicationStage)