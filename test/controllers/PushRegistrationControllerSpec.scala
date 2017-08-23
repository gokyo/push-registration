/*
 * Copyright 2017 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package controllers

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.codahale.metrics.Meter
import org.scalatest.concurrent.ScalaFutures
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.test.FakeApplication
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.pushregistration.domain.{NativeOS, PushRegistration}
import uk.gov.hmrc.pushregistration.metrics.PushRegistrationMetricsPublisher

import scala.collection.JavaConverters._
import scala.collection.mutable

class PushRegistrationControllerSpec extends UnitSpec with WithFakeApplication with ScalaFutures with StubApplicationConfiguration {

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  override lazy val fakeApplication = FakeApplication(additionalConfiguration = config)

  Seq(1,2).foreach (testId => {
    s"push registration live $testId" should {

      "return successfully with a 201 response" in new Success {
        val result: Result = await(controller.register()(getRequest(testId)))

        status(result) shouldBe 201
        testRepository.savedRegistration.get shouldBe getRegistration(testId)
        testPushRegistrationService.saveDetails shouldBe buildAuditCheck(testId)
      }

      "return successfully with a 200 response" in new SuccessUpdated {
        val result: Result = await(controller.register()(getRequest(testId)))

        status(result) shouldBe 200
        testRepository.savedRegistration.get shouldBe getRegistration(testId)
        testPushRegistrationService.saveDetails shouldBe buildAuditCheck(testId)
      }

      "return successfully with a 200 response when journeyId is supplied" in new SuccessUpdated {
        val result: Result = await(controller.register(journeyId)(getRequest(testId)))

        status(result) shouldBe 200
        testRepository.savedRegistration.get shouldBe getRegistration(testId)
        testPushRegistrationService.saveDetails shouldBe buildAuditCheck(testId)
      }

      "Return 500 result when exception is thrown from repository" in new DbaseFailure {

        val result: Result = await(controller.register(journeyId)(getRequest(testId)))
        status(result) shouldBe 500
        jsonBodyOf(result) shouldBe Json.parse("""{"code":"INTERNAL_SERVER_ERROR","message":"Internal server error"}""")
      }

      "Return 401 result when authority record does not contain a NINO" in new AuthWithoutNino {
        val result = await(controller.register()(getRequest(testId)))

        status(result) shouldBe 401
        testRepository.savedRegistration shouldBe None
        jsonBodyOf(result) shouldBe Json.parse("""{"code":"UNAUTHORIZED","message":"Bearer token is missing or not authorized"}""")
      }

      "Return 403 result when authority has low CL" in new AuthLowCL {
        val result = await(controller.register()(getRequest(testId)))

        status(result) shouldBe 403
        testRepository.savedRegistration shouldBe None
        jsonBodyOf(result) shouldBe Json.parse("""{"code":"UNAUTHORIZED","message":"Access denied!"}""")
      }

      "meter new registrations" in new Success {
        await(controller.register()(getRequest(testId)))

        val allMeters: mutable.Map[String, Meter] = PushRegistrationMetricsPublisher.registry.getMeters().asScala
        val name = if (testId == 1) "unknown" else NativeOS.getName(device.os)
        val meter = allMeters.getOrElse(s"push-registration.registrations.new.$name", fail(s"missing meter: $name"))

        val was = meter.getCount

        await(controller.register()(getRequest(testId)))

        val now = meter.getCount

        now shouldBe was + 1
      }
    }
  })

  "push registration live controller validation" should {

    "return BadRequest request when invalid json is submitted" in new Success {
      val result = await(controller.register()(registrationBadRequest))

      status(result) shouldBe 400
    }

    "return BadRequest request when invalid json with unknown OS is submitted" in new Success {
      val result = await(controller.register()(registrationBadRequestInvalidDevice))

      status(result) shouldBe 400
    }

    "return BadRequest request when json is submitted which breaks boundary rules" in new Success {

      def buildString(length:Int) = "a" * length

      val toTest = Seq(
        PushRegistration(buildString(1024+1), Some(device), None),
        PushRegistration("token1", Some(device.copy(osVersion = buildString(50+1))), None),
        PushRegistration("token1", Some(device.copy(appVersion = buildString(50+1))), None),
        PushRegistration("token2", Some(device.copy(model = buildString(100+1))), None)
      )

      toTest.foreach(registration => {
        val jsonRequest = fakeRequest(Json.toJson(registration)).withHeaders(acceptHeader)
        val result = await(controller.register()(jsonRequest))
        status(result) shouldBe 400
      })
    }

    "return 406 result when the headers are invalid" in new Success {
      val result: Result = await(controller.register()(jsonRegistrationRequestNoAcceptHeader))

      status(result) shouldBe 406
      jsonBodyOf(result) shouldBe Json.parse("""{"code":"ACCEPT_HEADER_INVALID","message":"The accept header is missing or invalid"}""")

    }
  }

  "register push Sandbox" should {

    "return successfully with a 201 response" in new SandboxSuccess {
      val result: Result = await(controller.register()(jsonRegistrationRequestTokenOnly))

      status(result) shouldBe 201
      testPushRegistrationService.saveDetails shouldBe Map.empty
    }
  }

}
