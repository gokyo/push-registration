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

import org.scalatest.concurrent.ScalaFutures
import play.api.libs.json.{JsArray, JsObject, Json}
import play.api.mvc.Result
import play.api.test.FakeApplication
import play.api.test.Helpers.contentAsJson
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.pushregistration.config.MicroserviceAuditConnector
import uk.gov.hmrc.pushregistration.controllers.EndpointController
import uk.gov.hmrc.pushregistration.domain.NativeOS.{Android, Windows}
import uk.gov.hmrc.pushregistration.domain.{Device, PushRegistration}
import uk.gov.hmrc.pushregistration.repository.PushRegistrationPersist
import uk.gov.hmrc.pushregistration.services.PushRegistrationService

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class EndpointControllerSpec extends UnitSpec with WithFakeApplication with ScalaFutures with StubApplicationConfiguration {
  implicit lazy val timeout = akka.util.Timeout(2 seconds)

  override lazy val fakeApplication = FakeApplication(additionalConfiguration = config)

  class TestRegisterEndpointRepository(save: Boolean, remove: Boolean, authId: String, registrations: Seq[PushRegistration] = Seq.empty) extends TestRepository {
    val persisted: Seq[PushRegistrationPersist] = registrations.map { r =>
      PushRegistrationPersist(BSONObjectID.generate, r.token, authId, r.device, r.endpoint)
    }
    override def saveEndpoint(token: String, endpoint: String): Future[Boolean] = Future.successful(save)

    override def removeToken(token: String): Future[Boolean] = Future.successful(remove)

    override def findByAuthId(authId: String): Future[Seq[PushRegistrationPersist]] = Future.successful(persisted)
  }
  trait Success extends Setup {
    val authId = "int-id"
    val registrations = Seq(
      PushRegistration("some-token", Some(Device(Android, "1.2", "3.4.5", "Quux+")), Some("device:end:point")),
      PushRegistration("other-token", Some(Device(Windows, "9.8", "7.6.5", "Grault")), Some("_RESOLVING_b697f2a5-2047_"))
    )
    val testRegisterEndpointsRepository = new TestRegisterEndpointRepository(true, true, authId, registrations)

    override val testPushRegistrationService = new TestPushRegistrationService(authConnector, testRegisterEndpointsRepository, MicroserviceAuditConnector)

    val controller = new EndpointController {
      override val service: PushRegistrationService = testPushRegistrationService
      override implicit val ec: ExecutionContext = ExecutionContext.global
    }
  }

  trait PartialFail extends Setup {
    val authId = "int-id"
    val registrations = Seq(PushRegistration("token", Some(Device(Android, "1.2", "3.4.5", "Quux+")), None))
    val testRegisterEndpointsRepository = new TestRegisterEndpointRepository(true, false, authId, registrations)

    override val testPushRegistrationService = new TestPushRegistrationService(authConnector, testRegisterEndpointsRepository, MicroserviceAuditConnector)

    val controller = new EndpointController {
      override val service: PushRegistrationService = testPushRegistrationService
      override implicit val ec: ExecutionContext = ExecutionContext.global
    }
  }

  "EndpointController registerEndpoints" should {

    "update the registrations successfully and return 200 success" in new Success {

      val result: Result = await(controller.registerEndpoints()(endpointRequest))

      status(result) shouldBe 200
    }

    "return 202 accepted given an issue updating the database" in new PartialFail {

      val result: Result = await(controller.registerEndpoints()(endpointRequest))

      status(result) shouldBe 202
    }

    "return 400 bad request given an invalid request" in new Success {
      val result: Result = await(controller.registerEndpoints()(jsonRegistrationRequestTokenAndDevice))

      status(result) shouldBe 400
    }
  }

  "EndpointController getEndpointsForAuthId" should {
    "return 200 success and endpoint details given an authority with endpoints" in new Success {

      val result: Result = await(controller.getEndpointsWithNativeOsForAuthId("id")(emptyRequestWithAcceptHeader))

      status(result) shouldBe 200
      contentAsJson(result) shouldBe an[JsObject]
    }

    "only include \"resolved\" endpoints" in new Success {

      val result: Result = await(controller.getEndpointsWithNativeOsForAuthId("id")(emptyRequestWithAcceptHeader))

      status(result) shouldBe 200
      contentAsJson(result) shouldBe Json.toJson(registrations.map(registration => (registration.endpoint.get, registration.device.get.os)).dropRight(1).toMap)
    }

    "return 404 if an authority does not have any endpoints" in new PartialFail {

      val result: Result = await(controller.getEndpointsWithNativeOsForAuthId("id")(emptyRequestWithAcceptHeader))

      status(result) shouldBe 404
    }
  }
}