package com.itv.scalapact.shared.http

import com.itv.scalapact.shared._
import fs2.{Strategy, Task}
import org.http4s.client.Client

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.language.{implicitConversions, postfixOps}

object ScalaPactHttpClient extends IScalaPactHttpClient {

  implicit def taskToFuture[A](x: => Task[A]): Future[A] = {

    val p: Promise[A] = Promise()

    x.unsafeAttemptRun() match {
      case Left(ex) =>
        p.failure(ex)
        ()

      case Right(r) =>
        p.success(r)
        ()
    }

    p.future
  }

  private val maxTotalConnections: Int = 1

  private implicit val strategy: Strategy = Http4sClientHelper.strategy

  def doRequest(simpleRequest: SimpleRequest): Future[SimpleResponse] =
    doRequestTask(Http4sClientHelper.doRequest, simpleRequest)

  def doInteractionRequest(url: String, ir: InteractionRequest, clientTimeout: Duration): Future[InteractionResponse] =
    doInteractionRequestTask(Http4sClientHelper.doRequest, url, ir, clientTimeout)

  def doRequestSync(simpleRequest: SimpleRequest): Either[Throwable, SimpleResponse] =
    doRequestTask(Http4sClientHelper.doRequest, simpleRequest).unsafeAttemptRun()

  def doInteractionRequestSync(url: String, ir: InteractionRequest, clientTimeout: Duration): Either[Throwable, InteractionResponse] =
    doInteractionRequestTask(Http4sClientHelper.doRequest, url, ir, clientTimeout).unsafeAttemptRun()

  def doRequestTask(performRequest: (SimpleRequest, Client) => Task[SimpleResponse], simpleRequest: SimpleRequest): Task[SimpleResponse] =
    performRequest(simpleRequest, Http4sClientHelper.buildPooledBlazeHttpClient(maxTotalConnections, 2.seconds))

  def doInteractionRequestTask(performRequest: (SimpleRequest, Client) => Task[SimpleResponse], url: String, ir: InteractionRequest, clientTimeout: Duration): Task[InteractionResponse] =
    performRequest(
      SimpleRequest( url, ir.path.getOrElse("") + ir.query.map(q => s"?$q").getOrElse(""), HttpMethod.maybeMethodToMethod(ir.method), ir.headers.getOrElse(Map.empty[String, String]), ir.body),
      Http4sClientHelper.buildPooledBlazeHttpClient(maxTotalConnections, clientTimeout)
    ).map { r =>
      InteractionResponse(
        status = Option(r.statusCode),
        headers = if(r.headers.isEmpty) None else Option(r.headers.map(p => p._1 -> p._2.mkString)),
        body = r.body,
        matchingRules = None
      )
    }

}