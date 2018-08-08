package io.hydrosphere.serving.manager.service.application.factory

import java.util.UUID

import cats.data.EitherT
import cats.implicits._
import io.hydrosphere.serving.manager.model.{DataProfileFields, HFResult, HResult, Result}
import io.hydrosphere.serving.manager.model.db._
import io.hydrosphere.serving.manager.service.application._
import io.hydrosphere.serving.manager.service.environment.AnyEnvironment
import Result.Implicits._
import io.hydrosphere.serving.contract.model_contract.ModelContract
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.manager.model.Result.ClientError
import io.hydrosphere.serving.manager.model.api.ops.ModelSignatureOps

import scala.concurrent.{ExecutionContext, Future}

class DAGApplicationFactory(val factoryParams: FactoryParams)(implicit executionContext: ExecutionContext) extends ApplicationFactory {

  override def create(req: CreateApplicationRequest): HFResult[Application] = {
    val f = for {
      dag <- EitherT(inferDagApp(req.executionGraph.stages, req.executionGraph.links))
      dagContract <- EitherT(Future.successful(inferDagContract(req.name, dag)))
    } yield {
      Application(-1, req.name, req.namespace, dagContract, dag, req.kafkaStreaming)
    }
    f.value
  }

  def ensureSingatures(stages: Seq[ApplicationStage]): HResult[Seq[ModelSignature]] = {
    Result.traverse(stages) { stage =>
      stage.signature.toHResult(ClientError(s"Signature is not defined for stage ${stage.key}"))
    }
  }

  private def inferDagContract(appName: String, dag: ApplicationExecutionGraph): HResult[ModelContract] = {
    val sources = dag.links.map(_.from)
    val sinks = dag.links.map(_.to)

    val roots = sources.filterNot(sinks.contains)
    val terminals = sinks.filterNot(sources.contains)

    for {
      inputSigs <- ensureSingatures(roots).right
      outputSigs <- ensureSingatures(terminals).right
    } yield {
      val inputs = inputSigs.fold(ModelSignature.defaultInstance) {
        case (sig1, sig2) => ModelSignatureOps.merge(sig1, sig2)
      }.inputs

      val outputs = outputSigs.fold(ModelSignature.defaultInstance) {
        case (sig1, sig2) => ModelSignatureOps.merge(sig1, sig2)
      }.outputs

      val signature = ModelSignature(
        signatureName = appName,
        inputs = inputs,
        outputs = outputs
      )

      ModelContract(
        modelName = appName,
        signatures = Seq(signature)
      )
    }
  }

  private def checkDAGNodeContracts(stagesInfo: Seq[ApplicationStage], linkIds: Seq[(UUID, UUID)]): HResult[ApplicationExecutionGraph] = {
    var errorNode = Option.empty[String]

    def _visit_and_check_contracts(nodeId: String): Unit = {
      val currentSignature = stagesInfo.find(_.key == nodeId).map(_.signature.get).get //FIXME naive

      val parents = linkIds.filter(_._2 == nodeId).map(_._1)

      if (parents.nonEmpty) {
        val parentSignatures = parents.flatMap(id => stagesInfo.find(_.key == id)).flatMap(_.signature) //FIXME naive
        val parentMetaSignature = parentSignatures.fold(ModelSignature.defaultInstance) {
          case (a, b) => ModelSignatureOps.merge(a, b)
        }
        val isCompatible = ModelSignatureOps.append(parentMetaSignature, currentSignature).isDefined
        if (isCompatible) {
          // propagate deeper into graph
          val children = linkIds.filter(_._1 == nodeId).map(_._2.toString)
          children.foreach(_visit_and_check_contracts)
        } else {
          errorNode = Some(nodeId)
        }
      }
    }

    val sources = linkIds.map(_._1)
    val sinks = linkIds.map(_._2)

    val roots = sources.filterNot(sinks.contains)
    val terminals = sinks.filterNot(sources.contains)

    roots.foreach { node =>
      _visit_and_check_contracts(node.toString)
    }

    errorNode match {
      case Some(errorNodeId) =>
        Result.clientError(s"Incompatible signature for node id=$errorNodeId")
      case None =>
        val fullLinks = linkIds.map { case (from, to) =>
          val source = stagesInfo.find(_.key == from.toString).get
          val destination = stagesInfo.find(_.key == to.toString).get
          StageLink(source, destination)
        }

        Result.ok(
          ApplicationExecutionGraph(
            stagesInfo,
            fullLinks
          )
        )
    }
  }

  private def inferDagApp(stages: Seq[ExecutionStageRequest], links: Seq[ExecutionLinkRequest]): HFResult[ApplicationExecutionGraph] = {
    val linkIds = links.map(x => x.from -> x.to)
    val stageIds = stages.map(_.key.getOrElse(UUID.randomUUID()))
    val dag = DAG(stageIds, linkIds)
    val f = for {
      _ <- EitherT(Future.successful(checkDAG(dag)))
      stagesInfo <- EitherT(getFullStageInfo(stages))
      result <- EitherT(Future.successful(checkDAGNodeContracts(stagesInfo, linkIds)))
    } yield {
      result
    }
    f.value
  }

  private def getFullStageInfo(stages: Seq[ExecutionStageRequest]) = {
    Result.traverseF(stages) { stageRequest =>
      val key = stageRequest.key.map(_.toString).getOrElse(ApplicationStage.randomKey)
      val f = for {
        services <- EitherT(inferServices(stageRequest.services))
        dataProfileFields = mergeServiceDataProfilingTypes(services)
        signature <- EitherT(Future.successful(inferStageSignature(services)))
      } yield {
        ApplicationStage.apply(key, services, Some(signature.withSignatureName(key)), dataProfileFields)
      }
      f.value
    }
  }

  private def checkCycles(dag: DAG[_]): HResult[Unit] = {
    if (dag.isAcyclic()) {
      Result.ok(Unit)
    } else {
      Result.clientError("Graph must be acyclic, but cycles are detected.")
    }
  }

  private def checkComponents(dag: DAG[_]): HResult[Unit] = {
    if (dag.isSingleComponent()) {
      Result.ok(Unit)
    } else {
      Result.clientError("Graph must contain single component, but multiple components detected.")
    }
  }

  private def checkDAG(dag: DAG[_]): HResult[Unit] = {
    for {
      _ <- checkCycles(dag).right
      _ <- checkComponents(dag).right
    } yield {
      Unit
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