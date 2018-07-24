package io.hydrosphere.serving.manager.service.application.executor

import io.hydrosphere.serving.manager.model.DataProfileFields

case class StageInfo(
  applicationRequestId: Option[String],
  signatureName: String,
  applicationId: Long,
  modelVersionId: Option[Long],
  stageId: String,
  applicationNamespace: Option[String],
  dataProfileFields: DataProfileFields = Map.empty
)
