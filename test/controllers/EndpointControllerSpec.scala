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
import play.api.mvc.Result
import play.api.test.FakeApplication
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.pushregistration.config.MicroserviceAuditConnector
import uk.gov.hmrc.pushregistration.controllers.EndpointController
import uk.gov.hmrc.pushregistration.repository.PushRegistrationPersist
import uk.gov.hmrc.pushregistration.services.PushRegistrationService

import scala.concurrent.{ExecutionContext, Future}

class EndpointControllerSpec extends UnitSpec with WithFakeApplication with ScalaFutures with StubApplicationConfiguration {

  override lazy val fakeApplication = FakeApplication(additionalConfiguration = config)

  class TestRegisterEndpointRepository(save: Boolean, remove: Boolean) extends TestRepository {
    val persist = PushRegistrationPersist(BSONObjectID.generate, "some-token", "authid", None)
    override def saveEndpoint(token: String, endpoint: String): Future[Boolean] = Future.successful(save)

    override def removeToken(token: String): Future[Boolean] = Future.successful(remove)
  }

  trait Success extends Setup {
    val testRegisterEndpointsRepository = new TestRegisterEndpointRepository(true, true)

    override val testPushRegistrationService = new TestPushRegistrationService(authConnector, testRegisterEndpointsRepository , MicroserviceAuditConnector)

    val controller = new EndpointController {
      override val service: PushRegistrationService = testPushRegistrationService
      override implicit val ec: ExecutionContext = ExecutionContext.global
    }
  }

  trait PartialFail extends Setup {
    val testRegisterEndpointsRepository = new TestRegisterEndpointRepository(true, false)

    override val testPushRegistrationService = new TestPushRegistrationService(authConnector, testRegisterEndpointsRepository , MicroserviceAuditConnector)

    val controller = new EndpointController {
      override val service: PushRegistrationService = testPushRegistrationService
      override implicit val ec: ExecutionContext = ExecutionContext.global
    }
  }

  "registerEndpoints EndpointController" should {

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
}
