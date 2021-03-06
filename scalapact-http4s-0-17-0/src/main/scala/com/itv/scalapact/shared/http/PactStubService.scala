package com.itv.scalapact.shared.http

import java.util.concurrent.{ExecutorService, Executors}

import com.itv.scalapact.shared._
import org.http4s.dsl._
import org.http4s.server.Server
import org.http4s.server.blaze.BlazeBuilder
import org.http4s.util.CaseInsensitiveString
import org.http4s.{HttpService, Request, Response, Status}
import HeaderImplicitConversions._
import ColourOuput._
import fs2.{Strategy, Task}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object PactStubService {

  private val nThreads: Int = 10
  private val executorService: ExecutorService = Executors.newFixedThreadPool(nThreads)

  private implicit val strategy: Strategy = Strategy.fromExecutionContext(ExecutionContext.fromExecutorService(executorService))

  def startServer(interactionManager: IInteractionManager)(implicit pactReader: IPactReader, pactWriter: IPactWriter): ScalaPactSettings => Unit = config => {
    println(("Starting ScalaPact Stubber on: http://" + config.giveHost + ":" + config.givePort.toString).white.bold)
    println(("Strict matching mode: " + config.giveStrictMode.toString).white.bold)

    runServer(interactionManager, nThreads)(pactReader, pactWriter)(config).awaitShutdown()
  }

  def runServer(interactionManager: IInteractionManager, connectionPoolSize: Int)(implicit pactReader: IPactReader, pactWriter: IPactWriter): ScalaPactSettings => IPactServer = config => PactServer {
    BlazeBuilder
      .bindHttp(config.givePort, config.giveHost)
      .withExecutionContext(ExecutionContext.fromExecutorService(executorService))
      .withIdleTimeout(60.seconds)
      .withConnectorPoolSize(connectionPoolSize)
      .mountService(PactStubService.service(interactionManager, config.giveStrictMode), "/")
      .run
  }

  def stopServer: IPactServer => Unit = server =>
    server.shutdown()

  private val isAdminCall: Request => Boolean = request =>
      request.headers.get(CaseInsensitiveString("X-Pact-Admin")).exists(h => h.value == "true")

  private def service(interactionManager: IInteractionManager, strictMatching: Boolean)(implicit pactReader: IPactReader, pactWriter: IPactWriter): HttpService =
    HttpService.lift { req =>
      matchRequestWithResponse(interactionManager, strictMatching, req)
    }

  private def matchRequestWithResponse(interactionManager: IInteractionManager, strictMatching: Boolean, req: Request)(implicit pactReader: IPactReader, pactWriter: IPactWriter): Task[Response] = {
    if(isAdminCall(req)) {

      req.method.name.toUpperCase match {
        case m if m == "GET" && req.pathInfo.startsWith("/stub/status") =>
          Ok()

        case m if m == "GET" && req.pathInfo.startsWith("/interactions") =>
          val output = pactWriter.pactToJsonString(Pact(PactActor(""), PactActor(""), interactionManager.getInteractions))
          Ok(output)

        case m if m == "POST" || m == "PUT" && req.pathInfo.startsWith("/interactions") =>
          pactReader.jsonStringToPact(req.bodyAsText.runLog.map(body => Option(body.mkString)).unsafeRun().getOrElse("")) match {
            case Right(r) =>
              interactionManager.addInteractions(r.interactions)

              val output = pactWriter.pactToJsonString(Pact(PactActor(""), PactActor(""), interactionManager.getInteractions))
              Ok(output)

            case Left(l) =>
              InternalServerError(l)
          }

        case m if m == "DELETE" && req.pathInfo.startsWith("/interactions") =>
          interactionManager.clearInteractions()

          val output = pactWriter.pactToJsonString(Pact(PactActor(""), PactActor(""), interactionManager.getInteractions))
          Ok(output)
      }

    }
    else {

      interactionManager.findMatchingInteraction(
        InteractionRequest(
          method = Option(req.method.name.toUpperCase),
          headers = req.headers,
          query = if(req.params.isEmpty) None else Option(req.params.toList.map(p => p._1 + "=" + p._2).mkString("&")),
          path = Option(req.pathInfo),
          body = req.bodyAsText.runLog.map(body => Option(body.mkString)).unsafeRun(),
          matchingRules = None
        ),
        strictMatching = strictMatching
      ) match {
        case Right(ir) =>
          Status.fromInt(ir.response.status.getOrElse(200)) match {
            case Right(code) =>
              Http4sRequestResponseFactory.buildResponse(
                status = IntAndReason(ir.response.status.getOrElse(200), None),
                headers = ir.response.headers.getOrElse(Map.empty),
                body = ir.response.body
              )

            case Left(l) =>
              InternalServerError(l.sanitized)
          }

        case Left(message) =>
          Http4sRequestResponseFactory.buildResponse(
            status = IntAndReason(598, Some("Pact Match Failure")),
            headers = Map("X-Pact-Admin" -> "Pact Match Failure"),
            body = Option(message)
          )
      }

    }
  }
}

case class PactServer(s: Server) extends IPactServer {

  def awaitShutdown(): Unit =
    s.awaitShutdown()

  def shutdown(): Unit =
    s.shutdownNow()

}