package com.itv.scalapact.shared.http

import com.itv.scalapact.shared.{HttpMethod, SimpleRequest}
import org.scalatest.{FunSpec, Matchers}

class Http4sRequestResponseFactorySpec extends FunSpec with Matchers {

  describe("Creating Http4s requests and responses") {

    it("should be able to manufacture a good request") {

      val simpleRequest = SimpleRequest(
        "http://localhost:8080",
        "/foo",
        HttpMethod.POST,
        Map(
          "Accept" -> "application/json",
          "Content-Type" -> "test/plain"
        ),
        Some("Greetings!")
      )

      val request = Http4sRequestResponseFactory.buildRequest(simpleRequest).run

      request.method.name shouldEqual "POST"
      request.pathInfo.contains("foo") shouldEqual true
      request.bodyAsText.runLog.run.mkString shouldEqual "Greetings!"

    }

    it("should be able to manufacture a good response") {

      val response = Http4sRequestResponseFactory.buildResponse(
        IntAndReason(404, Some("Not Found")),
        Map(
          "Content-Type" -> "test/plain"
        ),
        Some("Missing")
      ).run

      response.status.code shouldEqual 404
      response.bodyAsText.runLog.run.mkString shouldEqual "Missing"

    }

  }

}
