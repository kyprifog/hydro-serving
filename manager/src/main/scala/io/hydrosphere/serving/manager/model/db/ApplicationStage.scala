package io.hydrosphere.serving.manager.model.db

import java.util.UUID

import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.manager.model.DataProfileFields

case class ApplicationStage(
  key: String,
  services: Seq[DetailedServiceDescription],
  signature: Option[ModelSignature],
  dataProfileFields: DataProfileFields
)

object ApplicationStage {
  def randomKey = UUID.randomUUID().toString
}

case class DetailedServiceDescription(
  runtime: Runtime,
  modelVersion: ModelVersion,
  environment: Environment,
  weight: Int,
  signature: Option[ModelSignature]
) {
  def serviceDescription = ServiceKeyDescription(
    runtimeId = runtime.id,
    modelVersionId = Some(modelVersion.id),
    environmentId = Some(environment.id)
  )
}