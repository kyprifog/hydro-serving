package io.hydrosphere.serving.manager.service.application

import io.hydrosphere.serving.manager.model.db.ApplicationKafkaStream

case class CreateApplicationRequest(
  name: String,
  namespace: Option[String],
  executionGraph: ExecutionGraphRequest,
  kafkaStreaming: List[ApplicationKafkaStream]
)
