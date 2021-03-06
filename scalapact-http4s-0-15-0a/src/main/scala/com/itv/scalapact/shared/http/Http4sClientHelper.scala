package com.itv.scalapact.shared.http

import com.itv.scalapact.shared.{SimpleRequest, SimpleResponse}
import org.http4s.{BuildInfo, Response}
import org.http4s.client.Client
import org.http4s.client.blaze.{BlazeClientConfig, PooledHttp1Client}
import org.http4s.headers.{AgentProduct, `User-Agent`}

import scala.concurrent.duration._
import scalaz.concurrent.Task

object Http4sClientHelper {

  import HeaderImplicitConversions._

  private def blazeClientConfig(clientTimeout: Duration): BlazeClientConfig = BlazeClientConfig.defaultConfig.copy(
    requestTimeout = clientTimeout,
    userAgent = Option(`User-Agent`(AgentProduct("scala-pact", Option(BuildInfo.version)))),
    endpointAuthentication = false,
    customExecutor = None
  )

  private val extractResponse: Response => Task[SimpleResponse] = r =>
    r.bodyAsText.runLog[Task, String].map(_.mkString).map { b => SimpleResponse(r.status.code, r.headers, Some(b)) }

  def defaultClient: Client =
    buildPooledBlazeHttpClient(1, Duration(1, SECONDS))

  def buildPooledBlazeHttpClient(maxTotalConnections: Int, clientTimeout: Duration): Client =
    PooledHttp1Client(maxTotalConnections, blazeClientConfig(clientTimeout))

  val doRequest: (SimpleRequest, Client) => Task[SimpleResponse] = (request, httpClient) =>
    for {
      request  <- Http4sRequestResponseFactory.buildRequest(request)
      response <- httpClient.fetch[SimpleResponse](request)(extractResponse)
      _        <- httpClient.shutdown
    } yield response

}
