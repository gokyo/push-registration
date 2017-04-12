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
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.test.FakeApplication
import play.api.test.Helpers._
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.mongo.DatabaseUpdate
import uk.gov.hmrc.play.http.{Upstream4xxResponse, HeaderCarrier}
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.pushregistration.config.MicroserviceAuditConnector
import uk.gov.hmrc.pushregistration.connectors.Authority
import uk.gov.hmrc.pushregistration.controllers.FindPushRegistrationController
import uk.gov.hmrc.pushregistration.controllers.action.AccountAccessControlWithHeaderCheck
import uk.gov.hmrc.pushregistration.domain.{NativeOS, Device, PushRegistration}
import uk.gov.hmrc.pushregistration.repository.PushRegistrationPersist
import uk.gov.hmrc.pushregistration.services.PushRegistrationService

import scala.concurrent.{ExecutionContext, Future}


class FindPushRegistrationControllerSpec extends UnitSpec with WithFakeApplication with ScalaFutures with StubApplicationConfiguration {

  override lazy val fakeApplication = FakeApplication(additionalConfiguration = config)

  val device = Device(NativeOS.Android, "1.2.3", "1.3", "Nexus 5")
  val endpoint = "/some/endpoint"
  val registrationPersist = PushRegistrationPersist(BSONObjectID.generate, "token", "authId", Some(device), Some(endpoint))
  val registrationIncompletePersist = PushRegistrationPersist(BSONObjectID.generate, "token", "authId", Some(device), None)
  val found = new TestFindRepository(Seq(registrationPersist))
  val foundIncomplete = new TestFindRepository(Seq(registrationIncompletePersist))
  val foundRegistration = PushRegistration("token", Some(device), Some(endpoint))
  val foundIncompleteRegistration = PushRegistration("token", Some(device), None)

  class TestFindRepository(response:Seq[PushRegistrationPersist]) extends TestRepository {
    override def save(registration: PushRegistration, authId:String): Future[DatabaseUpdate[PushRegistrationPersist]] = Future.failed(new IllegalArgumentException("Not defined"))

    override def findByAuthId(authId: String): Future[Seq[PushRegistrationPersist]] = Future.successful(response)

    override def findIncompleteRegistrations(): Future[Seq[PushRegistrationPersist]] = Future.successful(response)
  }

  trait Success extends Setup {

    val testFinderRepository = found

    override val testPushRegistrationService = new TestPushRegistrationService(authConnector, testFinderRepository , MicroserviceAuditConnector)

    val controller = new FindPushRegistrationController {
      override val service: PushRegistrationService = testPushRegistrationService
      val testCompositeAction = new TestAccountAccessControlWithAccept(testAccess)
      override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
      override implicit val ec: ExecutionContext = ExecutionContext.global

    }
  }

  trait Incomplete extends Setup {
    val testFindRepository = foundIncomplete

    override val testPushRegistrationService = new TestPushRegistrationService(authConnector, testFindRepository , MicroserviceAuditConnector)

    val controller = new FindPushRegistrationController {
      override val service: PushRegistrationService = testPushRegistrationService
      val testCompositeAction = new TestAccountAccessControlWithAccept(testAccess)
      override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
      override implicit val ec: ExecutionContext = ExecutionContext.global
    }
  }

  trait NotFoundResult extends Setup {
    val testFinderRepository = new TestFindRepository(Seq.empty)

    override val testPushRegistrationService = new TestPushRegistrationService(authConnector, testFinderRepository , MicroserviceAuditConnector)

    val controller = new FindPushRegistrationController {
      override val service: PushRegistrationService = testPushRegistrationService
      val testCompositeAction = new TestAccountAccessControlWithAccept(testAccess)
      override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
      override implicit val ec: ExecutionContext = ExecutionContext.global
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
      val testCompositeAction = new TestAccountAccessControlWithAccept(testAccess)
      override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
      override implicit val ec: ExecutionContext = ExecutionContext.global
    }
  }

  "find PushNotificationController" should {

    "find the record successfully and return 200 success and Json" in new Success {

      val result: Result = await(controller.find("id")(emptyRequestWithAcceptHeader))

      status(result) shouldBe 200
      contentAsJson(result) shouldBe Json.toJson(Seq(foundRegistration))
      testPushRegistrationService.saveDetails shouldBe Map("authId" -> "id")
    }

    "find the record successfully with journeyId and return 200 success and Json" in new Success {

      val result: Result = await(controller.find("id", journeyId)(emptyRequestWithAcceptHeader))

      status(result) shouldBe 200
      contentAsJson(result) shouldBe Json.toJson(Seq(foundRegistration))
      testPushRegistrationService.saveDetails shouldBe Map("authId" -> "id")
    }

    "return 404 when the record cannot be found" in new NotFoundResult {

      val result: Result = await(controller.find("id")(emptyRequestWithAcceptHeader))

      status(result) shouldBe 404

      testPushRegistrationService.saveDetails shouldBe Map("authId" -> "id")
    }

  }

  "findIncompleteRegistrations PushNotificationController" should {

    "find unregistered tokens and return 200 success and Json" in new Incomplete {
      val result: Result = await(controller.findIncompleteRegistrations()(emptyRequestWithAcceptHeader))

      status(result) shouldBe 200
      contentAsJson(result) shouldBe Json.toJson(Seq(foundIncompleteRegistration))

    }

    "return 404 not found when there are no unregistered tokens" in new NotFoundResult {
      val result: Result = await(controller.findIncompleteRegistrations()(emptyRequestWithAcceptHeader))

      status(result) shouldBe 404
      contentAsJson(result) shouldBe Json.parse("""{"code":"NOT_FOUND","message":"No unregistered endpoints"}""")

    }
  }
}
