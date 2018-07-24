package io.hydrosphere.serving.manager.service.application

import cats.data.EitherT
import cats.implicits._
import io.hydrosphere.serving.contract.model_contract.ModelContract
import io.hydrosphere.serving.manager.ApplicationConfig
import io.hydrosphere.serving.manager.model.Result.ClientError
import io.hydrosphere.serving.manager.model.Result.Implicits._
import io.hydrosphere.serving.manager.model._
import io.hydrosphere.serving.manager.model.api.TensorExampleGenerator
import io.hydrosphere.serving.manager.model.api.json.TensorJsonLens
import io.hydrosphere.serving.manager.model.api.tensor_builder.SignatureBuilder
import io.hydrosphere.serving.manager.model.db._
import io.hydrosphere.serving.manager.repository.ApplicationRepository
import io.hydrosphere.serving.manager.service.application.executor.{ApplicationExecutor, ExecutorParams}
import io.hydrosphere.serving.manager.service.environment.{AnyEnvironment, EnvironmentManagementService}
import io.hydrosphere.serving.manager.service.internal_events.InternalManagerEventsPublisher
import io.hydrosphere.serving.manager.service.model_version.ModelVersionManagementService
import io.hydrosphere.serving.manager.service.runtime.RuntimeManagementService
import io.hydrosphere.serving.manager.service.service.{CreateServiceRequest, ServiceManagementService}
import io.hydrosphere.serving.monitoring.monitoring.MonitoringServiceGrpc
import io.hydrosphere.serving.profiler.profiler.DataProfilerServiceGrpc
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc
import io.hydrosphere.serving.tensorflow.tensor.TypedTensorFactory
import org.apache.logging.log4j.scala.Logging
import spray.json.JsObject

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class ApplicationManagementServiceImpl(
  applicationRepository: ApplicationRepository,
  modelVersionManagementService: ModelVersionManagementService,
  environmentManagementService: EnvironmentManagementService,
  serviceManagementService: ServiceManagementService,
  grpcClient: PredictionServiceGrpc.PredictionServiceStub,
  grpcClientForMonitoring: MonitoringServiceGrpc.MonitoringServiceStub,
  grpcClientForProfiler: DataProfilerServiceGrpc.DataProfilerServiceStub,
  internalManagerEventsPublisher: InternalManagerEventsPublisher,
  applicationConfig: ApplicationConfig,
  runtimeService: RuntimeManagementService
)(implicit val ex: ExecutionContext) extends ApplicationManagementService with Logging {

  def serveApplication(application: Application, request: PredictRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[PredictResponse] = {
    val executorParams = ExecutorParams(
      grpcClient = grpcClient,
      grpcClientForMonitoring = grpcClientForMonitoring,
      grpcClientForProfiler = grpcClientForProfiler,
      isShadowed = applicationConfig.shadowingOn
    )
    val f = for {
      executor <- EitherT.fromEither(ApplicationExecutor.forApplication(application, executorParams))
      result <- EitherT(executor.execute(request, tracingInfo))
    } yield result
    f.value
  }

  def serveGrpcApplication(data: PredictRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[PredictResponse] = {
    data.modelSpec match {
      case Some(modelSpec) =>
        applicationRepository.getByName(modelSpec.name).flatMap {
          case Some(app) =>
            serveApplication(app, data, tracingInfo)
          case None => Future.failed(new IllegalArgumentException(s"Application '${modelSpec.name}' is not found"))
        }
      case None => Future.failed(new IllegalArgumentException("ModelSpec is not defined"))
    }
  }

  def serveJsonApplication(jsonServeRequest: JsonServeRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[JsObject] = {
    getApplication(jsonServeRequest.targetId).flatMap {
      case Right(application) =>
        val signature = application.contract.signatures
          .find(_.signatureName == jsonServeRequest.signatureName)
          .toHResult(
            ClientError(s"Application ${jsonServeRequest.targetId} doesn't have a ${jsonServeRequest.signatureName} signature")
          )

        val ds = signature.right.map { sig =>
          new SignatureBuilder(sig).convert(jsonServeRequest.inputs).right.map { tensors =>
            PredictRequest(
              modelSpec = Some(
                ModelSpec(
                  name = application.name,
                  signatureName = jsonServeRequest.signatureName,
                  version = None
                )
              ),
              inputs = tensors.mapValues(_.toProto)
            )
          }
        }

        ds match {
          case Left(err) => Result.errorF(err)
          case Right(Left(tensorError)) => Result.clientErrorF(s"Tensor validation error: $tensorError")
          case Right(Right(request)) =>
            serveApplication(application, request, tracingInfo).map { result =>
              result.right.map(responseToJsObject)
            }
        }
      case Left(error) =>
        Result.errorF(error)
    }
  }

  override def getApplication(appId: Long): HFResult[Application] = {
    applicationRepository.get(appId).map {
      case Some(app) => Result.ok(app)
      case None => Result.clientError(s"Can't find application with ID $appId")
    }
  }

  def allApplications(): Future[Seq[Application]] = {
    applicationRepository.all()
  }

  def findVersionUsage(versionId: Long): Future[Seq[Application]] = {
    allApplications().map { apps =>
      apps.filter { app =>
        app.executionGraph.stages.exists { stage =>
          stage.services.exists { service =>
            service.serviceDescription.modelVersionId.contains(versionId)
          }
        }
      }
    }
  }

  def generateInputsForApplication(appId: Long, signatureName: String): HFResult[JsObject] = {
    getApplication(appId).map { result =>
      result.right.flatMap { app =>
        app.contract.signatures.find(_.signatureName == signatureName) match {
          case Some(signature) =>
            val data = TensorExampleGenerator(signature).inputs
            Result.ok(TensorJsonLens.mapToJson(data))
          case None => Result.clientError(s"Can't find signature '$signatureName")
        }
      }
    }
  }

  def createApplication(req: CreateApplicationRequest): HFResult[Application] = executeWithSync {
    val keys = for {
      stage <- req.executionGraph.stages
      service <- stage.services
    } yield {
      service.toDescription
    }
    val keySet = keys.toSet

    val f = for {
      graph <- EitherT(inferGraph(req.executionGraph))
      contract <- EitherT(inferAppContract(req.name, graph))
      app <- EitherT(composeAppF(req.name, req.namespace, graph, contract, req.kafkaStreaming))

      services <- EitherT.liftF(serviceManagementService.fetchServicesUnsync(keySet))
      existedServices = services.map(_.toServiceKeyDescription)
      _ <- EitherT(startServices(keySet -- existedServices))

      createdApp <- EitherT(applicationRepository.create(app).map(Result.ok))
    } yield {
      internalManagerEventsPublisher.applicationChanged(createdApp)
      createdApp
    }
    f.value
  }

  def deleteApplication(id: Long): HFResult[Application] =
    executeWithSync {
      getApplication(id).flatMap {
        case Right(application) =>
          val keysSet = application.executionGraph.stages.flatMap(_.services).toSet
          applicationRepository.delete(id)
            .flatMap { _ =>
              removeServiceIfNeeded(keysSet, id)
                .map { _ =>
                  internalManagerEventsPublisher.applicationRemoved(application)
                  Result.ok(application)
                }
            }
        case Left(error) =>
          Result.errorF(error)
      }
    }

  def updateApplication(req: UpdateApplicationRequest): HFResult[Application] = {
    executeWithSync {
      val res = for {
        oldApplication <- EitherT(getApplication(req.id))

        graph <- EitherT(inferGraph(req.executionGraph))
        contract <- EitherT(inferAppContract(req.name, graph))
        newApplication <- EitherT(composeAppF(req.name, req.namespace, graph, contract, req.kafkaStreaming.getOrElse(List.empty), req.id))

        keysSetOld = oldApplication.executionGraph.stages.flatMap(_.services).toSet
        keysSetNew = req.executionGraph.stages.flatMap(_.services.map(_.toDescription)).toSet

        _ <- EitherT(removeServiceIfNeeded(keysSetOld -- keysSetNew, req.id))
        _ <- EitherT(startServices(keysSetNew -- keysSetOld))

        _ <- EitherT(applicationRepository.update(newApplication).map(Result.ok))
      } yield {
        internalManagerEventsPublisher.applicationChanged(newApplication)
        newApplication
      }
      res.value
    }
  }

  private def executeWithSync[A](func: => HFResult[A]): HFResult[A] = {
    applicationRepository.getLockForApplications().flatMap { lockInfo =>
      func andThen {
        case Success(r) =>
          applicationRepository.returnLockForApplications(lockInfo)
            .map(_ => r)
        case Failure(f) =>
          applicationRepository.returnLockForApplications(lockInfo)
            .map(_ => Result.internalError(f, "executeWithSync failed"))
      }
    }
  }

  private def startServices(keysSet: Set[ServiceKeyDescription]): HFResult[Seq[Service]] = {
    logger.debug(keysSet)
    serviceManagementService.fetchServicesUnsync(keysSet).flatMap { services =>
      val toAdd = keysSet -- services.map(_.toServiceKeyDescription)
      Result.traverseF(toAdd.toSeq) { key =>
        serviceManagementService.addService(
          CreateServiceRequest(
            serviceName = key.toServiceName(),
            runtimeId = key.runtimeId,
            configParams = None,
            environmentId = key.environmentId,
            modelVersionId = key.modelVersionId
          )
        )
      }
    }
  }

  private def removeServiceIfNeeded(keysSet: Set[ServiceKeyDescription], applicationId: Long): HFResult[Seq[Service]] = {
    val servicesF = for {
      apps <- applicationRepository.getKeysNotInApplication(keysSet, applicationId)
      keysSetOld = apps.flatMap(_.executionGraph.stages.flatMap(_.services.map(_.serviceDescription))).toSet
      services <- serviceManagementService.fetchServicesUnsync(keysSet -- keysSetOld)
    } yield services

    servicesF.flatMap { services =>
      Future.traverse(services) { service =>
        serviceManagementService.deleteService(service.id)
      }.map(Result.sequence)
    }
  }

  private def composeAppF(name: String, namespace: Option[String], graph: ApplicationExecutionGraph, contract: ModelContract, kafkaStreaming: Seq[ApplicationKafkaStream], id: Long = 0) = {
    Result.okF(
      Application(
        id = id,
        name = name,
        namespace = namespace,
        contract = contract,
        executionGraph = graph,
        kafkaStreaming = kafkaStreaming.toList
      )
    )
  }

  private def mergeServiceDataProfilingTypes(services: Seq[DetailedServiceDescription]): DataProfileFields = {
    val maps = services.map { s =>
      s.modelVersion.dataProfileTypes.getOrElse(Map.empty)
    }
    maps.reduce((a, b) => a ++ b)
  }

  def inferServices(services: List[ServiceCreationDescription]): HFResult[Seq[DetailedServiceDescription]] = {
    Result.sequenceF {
      services.map { service =>
        service.modelVersionId match {
          case Some(vId) =>
            val f = for {
              version <- EitherT(modelVersionManagementService.get(vId))
              runtime <- EitherT(runtimeService.get(service.runtimeId))
              signature <- EitherT(findSignature(version, service.signatureName))
              environment <- EitherT(environmentManagementService.get(service.environmentId.getOrElse(AnyEnvironment.id)))
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

  private def responseToJsObject(rr: PredictResponse): JsObject = {
    val fields = rr.outputs.mapValues(v => TensorJsonLens.toJson(TypedTensorFactory.create(v)))
    JsObject(fields)
  }
}