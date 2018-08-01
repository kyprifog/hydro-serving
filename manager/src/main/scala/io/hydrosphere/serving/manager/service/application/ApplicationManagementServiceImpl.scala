package io.hydrosphere.serving.manager.service.application

import cats.data.EitherT
import cats.implicits._
import io.hydrosphere.serving.contract.model_signature.ModelSignature
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
import io.hydrosphere.serving.manager.service.application.factory.{ApplicationFactory, FactoryParams}
import io.hydrosphere.serving.manager.service.environment.EnvironmentManagementService
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

  private val factoryParams = FactoryParams(
    modelVersionManagementService,
    runtimeService,
    environmentManagementService
  )

  override def serveGrpcApplication(data: PredictRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[PredictResponse] = {
    val f = for {
      spec <- EitherT(Future.successful(data.modelSpec.toHResult(ClientError("ModelSpec is not defined"))))
      application <- EitherT(getApplication(spec.name))
      response <- EitherT(serveApplication(application, data, tracingInfo))
    } yield response
    f.value
  }

  override def serveJsonApplication(jsonServeRequest: JsonServeRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[JsObject] = {
    val f = for {
      application <- EitherT(getApplication(jsonServeRequest.targetId))
      signature <- EitherT(Future.successful(findSignature(application, jsonServeRequest.signatureName)))
      request <- requestToProto(application.name, signature, jsonServeRequest)
      response <- EitherT(serveApplication(application, request, tracingInfo))
    } yield responseToJsObject(response)
    f.value
  }

  override def getApplication(appId: Long): HFResult[Application] = {
    applicationRepository.get(appId).map {
      case Some(app) => Result.ok(app)
      case None => Result.clientError(s"Can't find application with id=$appId")
    }
  }

  override def getApplication(appName: String): HFResult[Application] = {
    applicationRepository.getByName(appName).map {
      case Some(app) => Result.ok(app)
      case None => Result.clientError(s"Can't find application with name=$appName")
    }
  }

  override def allApplications(): Future[Seq[Application]] = {
    applicationRepository.all()
  }

  override def findVersionUsage(versionId: Long): Future[Seq[Application]] = {
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

  override def generateInputsForApplication(appId: Long, signatureName: String): HFResult[JsObject] = {
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

  override def createApplication(req: CreateApplicationRequest): HFResult[Application] = executeWithSync {
    val keys = for {
      stage <- req.executionGraph.stages
      service <- stage.services
    } yield {
      service.toDescription
    }
    val keySet = keys.toSet

    val f = for {
      factory <- EitherT(Future.successful(ApplicationFactory.forRequest(req, factoryParams)))
      app <- EitherT(factory.create(req))

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

  override def updateApplication(req: UpdateApplicationRequest): HFResult[Application] = {
    executeWithSync {
      val createReq = req.toCreate
      val res = for {
        oldApplication <- EitherT(getApplication(req.id))

        factory <- EitherT(Future.successful(ApplicationFactory.forRequest(createReq, factoryParams)))
        newApplication <- EitherT(factory.create(createReq))

        keysSetOld = oldApplication.executionGraph.stages.flatMap(_.services.map(_.serviceDescription)).toSet
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

  override def deleteApplication(id: Long): HFResult[Application] =
    executeWithSync {
      getApplication(id).flatMap {
        case Right(application) =>
          val keysSet = application.executionGraph.stages.flatMap(_.services.map(_.serviceDescription)).toSet
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

  private def serveApplication(application: Application, request: PredictRequest, tracingInfo: Option[RequestTracingInfo]): HFResult[PredictResponse] = {
    val executorParams = ExecutorParams(
      grpcClient = grpcClient,
      grpcClientForMonitoring = grpcClientForMonitoring,
      grpcClientForProfiler = grpcClientForProfiler,
      isShadowed = applicationConfig.shadowingOn
    )
    val f = for {
      executor <- EitherT(Future.successful(ApplicationExecutor.forApplication(application, executorParams)))
      result <- EitherT(executor.execute(request, tracingInfo))
    } yield result
    f.value
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

  private def requestToProto(appName: String, signature: ModelSignature, jsonServeRequest: JsonServeRequest) = {
    for {
      inputs <- EitherT(Future.successful(new SignatureBuilder(signature).convert(jsonServeRequest.inputs)))
    } yield {
      PredictRequest(
        modelSpec = Some(
          ModelSpec(
            name = appName,
            signatureName = jsonServeRequest.signatureName,
            version = None
          )
        ),
        inputs = inputs.mapValues(_.toProto)
      )
    }
  }

  private def findSignature(application: Application, signatureName: String) = {
    application.contract.signatures
      .find(_.signatureName == signatureName)
      .toHResult(
        ClientError(s"Application ${application.id} doesn't have a $signatureName signature")
      )
  }

  private def responseToJsObject(rr: PredictResponse): JsObject = {
    val fields = rr.outputs.mapValues(v => TensorJsonLens.toJson(TypedTensorFactory.create(v)))
    JsObject(fields)
  }
}