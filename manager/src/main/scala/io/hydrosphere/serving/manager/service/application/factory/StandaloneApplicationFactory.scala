package io.hydrosphere.serving.manager.service.application.factory

import java.util.UUID

import cats.data.EitherT
import io.hydrosphere.serving.contract.model_contract.ModelContract
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.manager.model.db.{Application, ApplicationExecutionGraph, ApplicationStage, DetailedServiceDescription, Environment, ModelVersion, Runtime}
import io.hydrosphere.serving.manager.model.{HFResult, Result}
import io.hydrosphere.serving.manager.service.application.{CreateApplicationRequest, ServiceCreationDescription}
import io.hydrosphere.serving.manager.service.environment.{AnyEnvironment, EnvironmentManagementService}
import io.hydrosphere.serving.manager.service.model_version.ModelVersionManagementService
import io.hydrosphere.serving.manager.service.runtime.RuntimeManagementService

class StandaloneApplicationFactory(
  val factoryParams: FactoryParams
) extends ApplicationFactory {

  def inferContract(stage: Seq[ApplicationStage]): ModelContract = {
    stage.head.services.head.modelVersion.modelContract
  }

  override def create(req: CreateApplicationRequest): HFResult[Application] = {
    val stage = req.executionGraph.stages.head
    val service = stage.services.head
    val f = for {
      stage <- EitherT(createStage(stage.key.getOrElse(UUID.randomUUID()), service))
    } yield {
      Application(
        id = 0,
        name = req.name,
        namespace = req.namespace,
        contract = inferContract(stage),
        executionGraph = ApplicationExecutionGraph(
          stages = stage,
          links = Seq.empty
        ),
        kafkaStreaming = req.kafkaStreaming
      )
    }
    f.value
  }

  private def createStage(stageId: UUID, service: ServiceCreationDescription) = {
    service.modelVersionId match {
      case Some(vId) =>
        val f = for {
          version <- EitherT(factoryParams.modelVersionManagementService.get(vId))
          runtime <- EitherT(factoryParams.runtimeManagementService.get(service.runtimeId))
          environment <- EitherT(factoryParams.environmentManagementService.get(service.environmentId.getOrElse(AnyEnvironment.id)))
          signed <- EitherT(createDetailedServiceDesc(service, version, runtime, environment, None))
        } yield Seq(
          ApplicationStage(
            key = stageId,
            services = List(signed.copy(weight = 100)), // 100 since this is the only service in the app
            signature = None,
            dataProfileFields = signed.modelVersion.dataProfileTypes.getOrElse(Map.empty)
          )
        )
        f.value
      case None => Result.clientErrorF(s"$service doesn't have a modelversion")
    }
  }

  private def createDetailedServiceDesc(
    service: ServiceCreationDescription,
    modelVersion: ModelVersion,
    runtime: Runtime,
    environment: Environment,
    signature: Option[ModelSignature]
  ) = {
    Result.okF(
      DetailedServiceDescription(
        runtime,
        modelVersion,
        environment,
        service.weight,
        signature
      )
    )
  }
}
