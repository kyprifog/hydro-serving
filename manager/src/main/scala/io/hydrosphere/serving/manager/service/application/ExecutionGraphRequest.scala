package io.hydrosphere.serving.manager.service.application

import java.util.UUID

import io.hydrosphere.serving.manager.model.db.ServiceKeyDescription

case class ExecutionGraphRequest(
  stages: Seq[ExecutionStageRequest],
  links: Seq[ExecutionLinkRequest]
)

case class ExecutionStageRequest(
  key: Option[UUID],
  services: List[ServiceCreationDescription]
)

case class ExecutionLinkRequest(
  from: UUID,
  to: UUID
)

case class ServiceCreationDescription(
  runtimeId: Long,
  modelVersionId: Option[Long],
  environmentId: Option[Long],
  weight: Int,
  signatureName: String
) {
  def toDescription: ServiceKeyDescription = {
    ServiceKeyDescription(
      runtimeId,
      modelVersionId,
      environmentId
    )
  }
}