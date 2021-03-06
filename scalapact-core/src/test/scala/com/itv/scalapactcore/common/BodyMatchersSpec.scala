package com.itv.scalapactcore.common

import com.itv.scalapactcore.common.matchir.IrNodeMatchingRules
import org.scalatest.{FunSpec, Matchers}

import scala.language.implicitConversions

class BodyMatchersSpec extends FunSpec with Matchers {

  implicit def toOption[A](thing: A): Option[A] = Option(thing)

  import com.itv.scalapactcore.common.matching.BodyMatching._

  describe("Matching bodies") {

    it("should do trivial equality testing") {

      matchBodies(None, "hello", "hello")(IrNodeMatchingRules.empty).isSuccess shouldEqual true
      matchBodies(None, "hello", "this")(IrNodeMatchingRules.empty).isSuccess shouldEqual false

    }

    it("should handle no body and additional body matching") {

      withClue("None, None") {
        matchBodies(None, None, None)(IrNodeMatchingRules.empty).isSuccess shouldEqual true
      }

      withClue("None, Some") {
        matchBodies(None, None, "this")(IrNodeMatchingRules.empty).isSuccess shouldEqual true
      }

      withClue("Some, None") {
        matchBodies(None, "this", None)(IrNodeMatchingRules.empty).isSuccess shouldEqual false
      }

    }

  }

}
