/*
 * Copyright 2016 HM Revenue & Customs
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
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.test.FakeApplication
import play.api.test.Helpers._
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.api.domain.Registration
import uk.gov.hmrc.mongo.DatabaseUpdate
import uk.gov.hmrc.play.http.{Upstream4xxResponse, HeaderCarrier}
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.pushregistration.config.MicroserviceAuditConnector
import uk.gov.hmrc.pushregistration.connectors.Authority
import uk.gov.hmrc.pushregistration.controllers.FindPushRegistrationController
import uk.gov.hmrc.pushregistration.domain.PushRegistration
import uk.gov.hmrc.pushregistration.repository.PushRegistrationPersist
import uk.gov.hmrc.pushregistration.services.PushRegistrationService

import scala.concurrent.{ExecutionContext, Future}


class FindPushRegistrationControllerSpec extends UnitSpec with WithFakeApplication with ScalaFutures with StubApplicationConfiguration {

  override lazy val fakeApplication = FakeApplication(additionalConfiguration = config)

  val registrationPersist = PushRegistrationPersist(BSONObjectID.generate, "id", "token", "authId")
  val registration = Registration(registrationPersist.deviceId, registrationPersist.token)
  val found = new TestFindRepository(Some(registrationPersist))

  class TestFindRepository(response:Option[PushRegistrationPersist]) extends TestRepository {
    override def save(registration: PushRegistration, authId:String): Future[DatabaseUpdate[PushRegistrationPersist]] = Future.failed(new IllegalArgumentException("Not defined"))

    override def findByAuthId(authId: String): Future[Option[PushRegistrationPersist]] = Future.successful(response)
  }

  trait Success extends Setup {

    val testFinderRepository = found

    override val testPushRegistrationService = new TestPushRegistrationService(authConnector, testFinderRepository , MicroserviceAuditConnector)

    val controller = new FindPushRegistrationController {
      override val service: PushRegistrationService = testPushRegistrationService
    }
  }

  trait NotFoundResult extends Setup {
    val testFinderRepository = new TestFindRepository(None)

    override val testPushRegistrationService = new TestPushRegistrationService(authConnector, testFinderRepository , MicroserviceAuditConnector)

    val controller = new FindPushRegistrationController {
      override val service: PushRegistrationService = testPushRegistrationService
    }
  }

  trait AuthWithoutNino extends Setup {

    override val authConnector =  new TestAuthConnector(None) {
      override def grantAccess()(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Authority] = Future.failed(new Upstream4xxResponse("Error", 401, 401))
    }

    val testFinderRepository = found

    override val testPushRegistrationService = new TestPushRegistrationService(authConnector, testFinderRepository , MicroserviceAuditConnector)

    val controller = new FindPushRegistrationController {
      override val service: PushRegistrationService = testPushRegistrationService
    }
  }

  "find PushNotificationController" should {

    "find the record successfully and return 200 success and Json" in new Success {

      val result: Result = await(controller.find("id")(emptyRequest))

      status(result) shouldBe 200

      contentAsJson(result) shouldBe Json.toJson(registration)

      testPushRegistrationService.saveDetails shouldBe Map("authId" -> "id")
    }

    "return 404 when the record cannot be found" in new NotFoundResult {

      val result: Result = await(controller.find("id")(emptyRequest))

      status(result) shouldBe 404

      testPushRegistrationService.saveDetails shouldBe Map("authId" -> "id")
    }

  }

}
