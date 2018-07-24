package io.hydrosphere.serving.manager.service.application

import io.hydrosphere.serving.manager.model.db.ApplicationKafkaStream

case class UpdateApplicationRequest(
  id: Long,
  name: String,
  namespace: Option[String],
  executionGraph: ExecutionGraphRequest,
  kafkaStreaming: Option[Seq[ApplicationKafkaStream]]
)