package com.itv.scalapactcore.verifier

import com.itv.scalapact.shared._
import com.itv.scalapactcore.common.matching.InteractionMatchers._
import com.itv.scalapact.shared.ColourOuput._
import com.itv.scalapact.shared.http.ScalaPactHttpClient
import com.itv.scalapactcore.common._

import scala.util.Left
import RightBiasEither._

object Verifier {

  def verify(loadPactFiles: String => ScalaPactSettings => ConfigAndPacts, pactVerifySettings: PactVerifySettings)(implicit pactReader: IPactReader): ScalaPactSettings => Boolean = arguments => {

    val pacts: List[Pact] = if(arguments.localPactFilePath.isDefined) {
      println(s"Attempting to use local pact files at: '${arguments.localPactFilePath.getOrElse("<path missing>")}'".white.bold)
      loadPactFiles("pacts")(arguments).pacts
    } else {

      val versionConsumers =
        pactVerifySettings.consumerNames.map(c => VersionedConsumer(c, "/latest")) ++
          pactVerifySettings.versionedConsumerNames.map(vc => vc.copy(version = "/version/" + vc.version))

      val latestPacts : List[Pact] = versionConsumers.map { consumer =>
        ValidatedDetails.buildFrom(consumer.name, pactVerifySettings.providerName, pactVerifySettings.pactBrokerAddress, consumer.version) match {
          case Left(l) =>
            println(l.red)
            None

          case Right(v) =>
            fetchAndReadPact(v.validatedAddress.address + "/pacts/provider/" + v.providerName + "/consumer/" + v.consumerName + v.consumerVersion)
        }
      }.collect {
        case Some(s) => s
      }

      latestPacts
    }

    println(s"Verifying against '${arguments.giveHost}' on port '${arguments.givePort}' with a timeout of ${arguments.clientTimeout.map(_.toSeconds.toString).getOrElse("<unspecified>")} second(s).".white.bold)

    val startTime = System.currentTimeMillis().toDouble

    val pactVerifyResults = pacts.map { pact =>
      PactVerifyResult(
        pact = pact,
        results = pact.interactions.map { interaction =>

          val maybeProviderState =
            interaction
              .providerState
              .map(p => ProviderState(p, PartialFunction(pactVerifySettings.providerStates)))

          val result = (doRequest(arguments, maybeProviderState) andThen attemptMatch(arguments.giveStrictMode, List(interaction)))(interaction.request)

          PactVerifyResultInContext(result, interaction.description)
        }
      )
    }

    val endTime = System.currentTimeMillis().toDouble
    val testCount = pactVerifyResults.flatMap(_.results).length
    val failureCount = pactVerifyResults.flatMap(_.results).count(_.result.isLeft)

    pactVerifyResults.foreach { result =>
      val content = JUnitXmlBuilder.xml(
        name = result.pact.consumer.name + " - " + result.pact.provider.name,
        tests = testCount,
        failures = failureCount,
        time = endTime - startTime / 1000,
        testCases = result.results.map { res =>
          res.result match {
            case Right(r) =>
              JUnitXmlBuilder.testCasePass(res.context)

            case Left(l) =>
              JUnitXmlBuilder.testCaseFail("Failure: " + res.context, l)
          }
        }
      )
      JUnitWriter.writePactVerifyResults(result.pact.consumer.name)(result.pact.provider.name)(content.toString)
    }

    pactVerifyResults.foreach { result =>
      println(("Results for pact between " + result.pact.consumer.name + " and " + result.pact.provider.name).white.bold)
      result.results.foreach { res =>
        res.result match {
          case Right(r) =>
            println((" - [  OK  ] " + res.context).green)

          case Left(l) =>
            println((" - [FAILED] " + res.context + "\n" + l).red)
        }
      }
    }

    failureCount == 0
  }

  // NOTE: Can't use flatMap due to scala 2.10.6
  private def attemptMatch(strictMatching: Boolean, interactions: List[Interaction]): Either[String, InteractionResponse] => Either[String, Interaction] = {
    case Right(i) =>
      matchResponse(strictMatching, interactions)(i)

    case Left(s) =>
      Left(s)
  }

  private def doRequest(arguments: ScalaPactSettings, maybeProviderState: Option[ProviderState]): InteractionRequest => Either[String, InteractionResponse] = interactionRequest => {
    val baseUrl = s"${arguments.giveProtocol}://" + arguments.giveHost + ":" + arguments.givePort.toString
    val clientTimeout = arguments.giveClientTimeout

    try {

      maybeProviderState match {
        case Some(ps) =>
          println("--------------------".yellow.bold)
          println(s"Attempting to run provider state: ${ps.key}".yellow.bold)

          val success = ps.f(ps.key)

          if(success)
            println(s"Provider state ran successfully".yellow.bold)
          else
            println(s"Provider state run failed".red.bold)

          println("--------------------".yellow.bold)

          if(!success) {
            throw new ProviderStateFailure(ps.key)
          }

        case None =>
          // No provider state run needed
      }

    } catch {
      case t: Throwable =>
        if(maybeProviderState.isDefined) {
          println(s"Error executing unknown provider state function with key: ${maybeProviderState.map(_.key).getOrElse("<missing key>")}".red)
        } else {
          println("Error executing unknown provider state function!".red)
        }
        throw t
    }

    try {
      InteractionRequest.unapply(interactionRequest) match {
        case Some((Some(_), Some(_), _, _, _, _)) =>

          ScalaPactHttpClient.doInteractionRequestSync(baseUrl, interactionRequest, clientTimeout)
            .leftMap { t =>
              println(s"Error in response: ${t.getMessage}".red)
              t.getMessage
            }

        case _ => Left("Invalid request was missing either method or path: " + interactionRequest.renderAsString)

      }
    } catch {
      case e: Throwable =>
        Left(e.getMessage)
    }

  }

  private def fetchAndReadPact(address: String)(implicit pactReader: IPactReader): Option[Pact] = {
    println(s"Attempting to fetch pact from pact broker at: $address".white.bold)

    ScalaPactHttpClient.doRequestSync(SimpleRequest(address, "", HttpMethod.GET, Map("Accept" -> "application/json"), None)).map {
      case r: SimpleResponse if r.is2xx =>
        val pact = r.body.map(pactReader.jsonStringToPact).flatMap {
          case Right(p) => Option(p)
          case Left(msg) =>
            println(s"Error: $msg".yellow)
            None
        }

        if(pact.isEmpty) {
          println("Could not convert good response to Pact:\n" + r.body.getOrElse(""))
          pact
        } else pact

      case _ =>
        println(s"Failed to load consumer pact from: $address".red)
        None
    } match {
      case Right(p) =>
        p
      case Left(e) =>
        println(s"Error: ${e.getMessage}".yellow)
        None
    }

  }

}

case class PactVerifyResult(pact: Pact, results: List[PactVerifyResultInContext])

case class PactVerifyResultInContext(result: Either[String, Interaction], context: String)

class ProviderStateFailure(key: String) extends Exception()

case class ProviderState(key: String, f: String => Boolean)
case class VersionedConsumer(name: String, version: String)
case class PactVerifySettings(providerStates: (String => Boolean), pactBrokerAddress: String, projectVersion: String, providerName: String, consumerNames: List[String], versionedConsumerNames: List[VersionedConsumer])

case class ValidatedDetails(validatedAddress: ValidPactBrokerAddress, providerName: String, consumerName: String, consumerVersion: String)

object ValidatedDetails {

  def buildFrom(consumerName: String, providerName: String, pactBrokerAddress: String, consumerVersion: String): Either[String, ValidatedDetails] =
    for {
      consumerName     <- Helpers.urlEncode(consumerName)
      providerName     <- Helpers.urlEncode(providerName)
      validatedAddress <- PactBrokerAddressValidation.checkPactBrokerAddress(pactBrokerAddress)
    } yield ValidatedDetails(validatedAddress, providerName, consumerName, consumerVersion)

}