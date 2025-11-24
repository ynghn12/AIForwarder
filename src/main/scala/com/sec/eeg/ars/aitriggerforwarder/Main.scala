package com.sec.eeg.ars.aitriggerforwarder

import java.util.concurrent.atomic.AtomicLong

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.stream.{ActorMaterializer, Materializer}
import akka.pattern.after
import org.apache.logging.log4j.LogManager
import com.typesafe.config.ConfigFactory
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object JsonFormats extends DefaultJsonProtocol {
  implicit val aiTriggerFormat: RootJsonFormat[AiTriggerRequest] = jsonFormat2(AiTriggerRequest)
  implicit val responseStatusFormat: RootJsonFormat[ResponseStatus] = jsonFormat1(ResponseStatus)
}

final case class AiTriggerRequest(eqpid: String, scname: String)
final case class ResponseStatus(status: String)
final case class EqpInfo(id: String, process: String, model: String, lineDesc: String)

final case class ForwarderConfig(
    interface: String,
    port: Int,
    earsUrl: String,
    earsTimeout: FiniteDuration,
    responseStatus: String)

object ForwarderConfig {
  def load(): ForwarderConfig = {
    val config = ConfigFactory.load().getConfig("forwarder")
    ForwarderConfig(
      interface = config.getString("interface"),
      port = config.getInt("port"),
      earsUrl = config.getString("ears.url"),
      earsTimeout = config.getDuration("ears.timeout").toMillis.millis,
      responseStatus = config.getString("response-pass-status")
    )
  }
}

trait EqpInfoRepository {
  def find(eqpid: String): Future[Option[EqpInfo]]
}

/**
  * Placeholder Mongo repository. Replace with real MongoDB lookup later.
  */
class StubEqpInfoRepository()(implicit ec: ExecutionContext) extends EqpInfoRepository {
  override def find(eqpid: String): Future[Option[EqpInfo]] = {
    // TODO: replace with real MongoDB query to EQP_INFO
    Future.successful(Some(EqpInfo(id = eqpid, process = "PROC", model = "MODEL", lineDesc = "LINE")))
  }
}

class EarsClient(config: ForwarderConfig)(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext) {
  private val logger = LogManager.getLogger(getClass)
  private val http = Http(system)

  def callEars(req: AiTriggerRequest, eqp: EqpInfo, txn: String): Future[HttpResponse] = {
    val headers = List(
      RawHeader("category", "recovery"),
      RawHeader("process", eqp.process),
      RawHeader("model", eqp.model),
      RawHeader("line", eqp.lineDesc),
      RawHeader("eqpid", req.eqpid),
      RawHeader("name", req.scname),
      RawHeader("txn", txn),
      RawHeader("triggeredBy", "AI")
    )

    val bodyJson = JsObject(
      "@Trigger" -> JsString("AI"),
      "@UserInfo" -> JsString("AI Server"),
      "@StartStep" -> JsString("1"),
      "UserText" -> JsString(s"Scenario=${req.scname}, Eqpid=${req.eqpid}")
    )

    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = config.earsUrl,
      headers = headers,
      entity = HttpEntity(ContentTypes.`application/json`, bodyJson.compactPrint)
    )

    val responseF = http.singleRequest(request)
    val timeoutF = after(config.earsTimeout, system.scheduler)(
      Future.failed(new java.util.concurrent.TimeoutException(s"EARS call timed out after ${config.earsTimeout.toMillis}ms"))
    )

    val start = System.nanoTime()
    Future.firstCompletedOf(List(responseF, timeoutF)).map { resp =>
      val elapsedMs = (System.nanoTime() - start) / 1000000
      logger.info(s"EARS call finished: txn=$txn, url=${config.earsUrl}, status=${resp.status.intValue()}, elapsedMs=$elapsedMs")
      resp
    }
  }
}

object TxnGenerator {
  private val seq = new AtomicLong(0L)
  def next(): String = s"${System.currentTimeMillis()}-${seq.getAndIncrement()}"
}

class ForwarderRoutes(
    config: ForwarderConfig,
    repo: EqpInfoRepository,
    earsClient: EarsClient
)(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext) {
  import JsonFormats._
  private val logger = LogManager.getLogger(getClass)

  private val exceptionHandler: ExceptionHandler = ExceptionHandler {
    case ex: Throwable =>
      logger.error("Unhandled exception in route", ex)
      complete(StatusCodes.InternalServerError)
  }

  val route: Route = handleExceptions(exceptionHandler) {
    path("ai_trigger") {
      post {
        extractClientIP { ip =>
          entity(as[AiTriggerRequest]) { req =>
            val txn = TxnGenerator.next()
            val trimmedEqp = Option(req.eqpid).getOrElse("").trim
            val trimmedScn = Option(req.scname).getOrElse("").trim

            if (trimmedEqp.isEmpty || trimmedScn.isEmpty) {
              logger.error(s"Invalid request: txn=$txn, eqpid='${req.eqpid}', scname='${req.scname}', clientIp=$ip")
              complete(StatusCodes.InternalServerError)
            } else {
              logger.info(s"Received AI trigger: txn=$txn, eqpid=$trimmedEqp, scname=$trimmedScn, clientIp=$ip")
              onComplete(repo.find(trimmedEqp)) {
                case scala.util.Success(Some(eqpInfo)) =>
                  onComplete(earsClient.callEars(req.copy(eqpid = trimmedEqp, scname = trimmedScn), eqpInfo, txn)) {
                    case scala.util.Success(resp) if resp.status.isSuccess() =>
                      resp.discardEntityBytes()
                      complete(StatusCodes.OK -> ResponseStatus(config.responseStatus))
                    case scala.util.Success(resp) =>
                      resp.discardEntityBytes()
                      logger.error(s"EARS returned non-success: txn=$txn, status=${resp.status}")
                      complete(StatusCodes.InternalServerError)
                    case scala.util.Failure(ex) =>
                      logger.error(s"EARS call failed: txn=$txn, eqpid=$trimmedEqp, scname=$trimmedScn", ex)
                      complete(StatusCodes.InternalServerError)
                  }
                case scala.util.Success(None) =>
                  logger.error(s"EQP_INFO not found: txn=$txn, eqpid=$trimmedEqp")
                  complete(StatusCodes.InternalServerError)
                case scala.util.Failure(ex) =>
                  logger.error(s"Mongo lookup failed: txn=$txn, eqpid=$trimmedEqp", ex)
                  complete(StatusCodes.InternalServerError)
              }
            }
          }
        }
      }
    }
  }
}

object Main extends App {
  implicit val system: ActorSystem = ActorSystem("ai-trigger-forwarder")
  implicit val mat: Materializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  private val logger = LogManager.getLogger(getClass)
  private val config = ForwarderConfig.load()

  private val repo = new StubEqpInfoRepository()
  private val earsClient = new EarsClient(config)
  private val routes = new ForwarderRoutes(config, repo, earsClient)

  private val bindingF = Http().bindAndHandle(routes.route, config.interface, config.port)

  bindingF.onComplete {
    case scala.util.Success(binding) =>
      val address = binding.localAddress
      logger.info(s"AITriggerForwarder started on ${address.getHostString}:${address.getPort}")
    case scala.util.Failure(ex) =>
      logger.error("Failed to start AITriggerForwarder", ex)
      system.terminate()
  }

  sys.addShutdownHook {
    logger.info("Shutting down AITriggerForwarder")
    system.terminate()
  }
}
