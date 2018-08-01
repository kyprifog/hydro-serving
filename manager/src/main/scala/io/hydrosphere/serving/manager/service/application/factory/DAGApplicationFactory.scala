package io.hydrosphere.serving.manager.service.application.factory

import cats.data.EitherT
import cats.implicits._
import io.hydrosphere.serving.manager.model.{DataProfileFields, HFResult, HResult, Result}
import io.hydrosphere.serving.manager.model.db._
import io.hydrosphere.serving.manager.service.application._
import io.hydrosphere.serving.manager.service.environment.AnyEnvironment
import Result.Implicits._
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.manager.model.api.ops.ModelSignatureOps

import scala.concurrent.{ExecutionContext, Future}

class DAGApplicationFactory(val factoryParams: FactoryParams)(implicit executionContext: ExecutionContext) extends ApplicationFactory {
  override def create(req: CreateApplicationRequest): HFResult[Application] = {
    ???
  }

  private def inferGraph(executionGraphRequest: ExecutionGraphRequest): HFResult[ApplicationExecutionGraph] = {
    val appStages = inferPipelineApp(executionGraphRequest.stages)
    EitherT(appStages).map { stages =>
      ApplicationExecutionGraph(
        stages.toList,
        ???
      )
    }.value
  }

  private def inferDagApp(stages: Seq[ExecutionStageRequest], links: Seq[ExecutionLinkRequest]) = {
    for {
      stagesInfo <- EitherT(getFullServiceInfo(stages))
    } yield {
      val sources = links.map(_.from)
      val sinks = links.map(_.to)

      val roots = sources.filterNot(sinks.contains)
      val terminals = sinks.filterNot(sources.contains)
    }
  }

  private def getFullServiceInfo(stages: Seq[ExecutionStageRequest]) = {
    Result.traverseF(stages) { stageRequest =>
      val key = stageRequest.key.map(_.toString).getOrElse(ApplicationStage.randomKey)
      val f = for {
        services <- EitherT(inferServices(stageRequest.services))
        dataProfileFields = mergeServiceDataProfilingTypes(services)
        signature <- EitherT(Future.successful(inferStageSignature(services)))
      } yield {
        key -> ApplicationStage.apply(key, services, Some(signature.withSignatureName(key)), dataProfileFields)
      }
      f.value
    }
  }

  def checkCycles(req: ExecutionGraphRequest): HResult[ExecutionGraphRequest] = ???

  def checkComponents(req: ExecutionGraphRequest): HResult[ExecutionGraphRequest] = ???

  private def checkDAG(req: ExecutionGraphRequest): HResult[ExecutionGraphRequest] = {
    for {
      acycled <- checkCycles(req).right
      singleComponent <- checkComponents(acycled).right
    } yield {
      singleComponent
    }
  }

  private def inferPipelineApp(stages: Seq[ExecutionStageRequest]): HFResult[Seq[ApplicationStage]] = {
    Result.sequenceF {
      stages.zipWithIndex.map {
        case (stage, id) =>
          val f = for {
            services <- EitherT(inferServices(stage.services))
            stageSigs <- EitherT(Future.successful(inferStageSignature(services)))
          } yield {
            ApplicationStage(
              key = stage.key.map(_.toString).getOrElse(ApplicationStage.randomKey),
              services = services.toList,
              signature = Some(stageSigs.withSignatureName(id.toString)),
              dataProfileFields = mergeServiceDataProfilingTypes(services)
            )
          }
          f.value
      }
    }
  }

  private def inferStageSignature(serviceDescs: Seq[DetailedServiceDescription]): HResult[ModelSignature] = {
    val signatures = serviceDescs.map { service =>
      service.signature match {
        case Some(sig) => Result.ok(sig)
        case None => Result.clientError(s"$service doesn't have a signature")
      }
    }
    val errors = signatures.filter(_.isLeft).map(_.left.get)
    if (errors.nonEmpty) {
      Result.clientError(s"Errors while inferring stage signature: $errors")
    } else {
      val values = signatures.map(_.right.get)
      Result.ok(
        values.foldRight(ModelSignature.defaultInstance) {
          case (sig1, sig2) => ModelSignatureOps.merge(sig1, sig2)
        }
      )
    }
  }

  private def inferServices(services: List[ServiceCreationDescription]): HFResult[Seq[DetailedServiceDescription]] = {
    Result.sequenceF {
      services.map { service =>
        service.modelVersionId match {
          case Some(vId) =>
            val f = for {
              version <- EitherT(factoryParams.modelVersionManagementService.get(vId))
              runtime <- EitherT(factoryParams.runtimeManagementService.get(service.runtimeId))
              signature <- EitherT(findSignature(version, service.signatureName))
              environment <- EitherT(factoryParams.environmentManagementService.get(service.environmentId.getOrElse(AnyEnvironment.id)))
              signed <- EitherT(createDetailedServiceDesc(service, version, runtime, environment, Some(signature)))
            } yield signed
            f.value
          case None => Result.clientErrorF(s"$service doesn't have a modelversion")
        }
      }
    }
  }

  private def findSignature(version: ModelVersion, signature: String) = {
    Future.successful {
      version.modelContract.signatures
        .find(_.signatureName == signature)
        .toHResult(Result.ClientError(s"Can't find signature $signature in $version"))
    }
  }

  private def createDetailedServiceDesc(service: ServiceCreationDescription, modelVersion: ModelVersion, runtime: Runtime, environment: Environment, signature: Option[ModelSignature]) = {
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

  private def mergeServiceDataProfilingTypes(services: Seq[DetailedServiceDescription]): DataProfileFields = {
    val maps = services.map { s =>
      s.modelVersion.dataProfileTypes.getOrElse(Map.empty)
    }
    maps.reduce((a, b) => a ++ b)
  }
}
