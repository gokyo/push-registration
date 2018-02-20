/*
 * Copyright 2018 HM Revenue & Customs
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
import org.scalatest.concurrent.ScalaFutures
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.test.FakeApplication
import play.api.test.Helpers._
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.http.{HeaderCarrier, Upstream4xxResponse}
import uk.gov.hmrc.mongo.DatabaseUpdate
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.pushregistration.config.MicroserviceAuditConnector
import uk.gov.hmrc.pushregistration.controllers.FindPushRegistrationController
import uk.gov.hmrc.pushregistration.controllers.action.{AccountAccessControlWithHeaderCheck, Authority}
import uk.gov.hmrc.pushregistration.domain.{Device, NativeOS, PushRegistration}
import uk.gov.hmrc.pushregistration.repository.PushRegistrationPersist
import uk.gov.hmrc.pushregistration.services.PushRegistrationService

import scala.concurrent.{ExecutionContext, Future}

class FindPushRegistrationControllerSpec extends UnitSpec with WithFakeApplication with ScalaFutures with StubApplicationConfiguration {

  implicit val system = ActorSystem()
  implicit val am = ActorMaterializer()

  override lazy val fakeApplication = FakeApplication(additionalConfiguration = config)

  val device = Device(NativeOS.Android, "1.2.3", "1.3", "Nexus 5")
  val endpoint = "/some/endpoint"
  val registrationPersist = PushRegistrationPersist(BSONObjectID.generate, "token", "authId", Some(device), Some(endpoint))
  val registrationIncompletePersist = PushRegistrationPersist(BSONObjectID.generate, "token", "authId", Some(device), None)
  val found = new TestFindRepository(Seq(registrationPersist), 5)
  val foundIncomplete = new TestFindRepository(Seq(registrationIncompletePersist), 0)
  val foundRegistration = PushRegistration("token", Some(device), Some(endpoint))
  val foundIncompleteRegistration = PushRegistration("token", Some(device), None)

  class TestFindRepository(response:Seq[PushRegistrationPersist], stale: Int) extends TestRepository {
    override def save(registration: PushRegistration, authId:String): Future[DatabaseUpdate[PushRegistrationPersist]] = Future.failed(new IllegalArgumentException("Not defined"))

    override def findByAuthId(authId: String): Future[Seq[PushRegistrationPersist]] = Future.successful(response)

    override def findIncompleteRegistrations(platforms: Seq[NativeOS], maxRows: Int): Future[Seq[PushRegistrationPersist]] = Future.successful(response)

    override def findTimedOutRegistrations(timeout: Long, maxRows: Int): Future[Seq[PushRegistrationPersist]] = Future.successful(response)

    override def removeStaleRegistrations(keep: Seq[NativeOS], timeoutMilliseconds: Long): Future[Int] = Future.successful(stale)
  }

  trait Success extends Setup {
    val testLockRepository = new TestLockRepository
    val testFinderRepository = found
    override val testPushRegistrationService = new TestPushRegistrationService(testAccess, testFinderRepository, testLockRepository, MicroserviceAuditConnector)

    val controller = new FindPushRegistrationController {
      override val service: PushRegistrationService = testPushRegistrationService
      val testCompositeAction = new TestAccountAccessControlWithAccept(testAccess)
      override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
      override implicit val ec: ExecutionContext = ExecutionContext.global

    }
  }

  trait Incomplete extends Setup {
    val testLockRepository = new TestLockRepository
    val testFindRepository = foundIncomplete
    override val testPushRegistrationService = new TestPushRegistrationService(testAccess, testFindRepository, testLockRepository, MicroserviceAuditConnector)

    val controller = new FindPushRegistrationController {
      override val service: PushRegistrationService = testPushRegistrationService
      val testCompositeAction = new TestAccountAccessControlWithAccept(testAccess)
      override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
      override implicit val ec: ExecutionContext = ExecutionContext.global
    }
  }

  trait NotFoundResult extends Setup {
    val testLockRepository = new TestLockRepository
    val testFinderRepository = new TestFindRepository(Seq.empty, 0)
    override val testPushRegistrationService = new TestPushRegistrationService(testAccess, testFinderRepository, testLockRepository, MicroserviceAuditConnector)

    val controller = new FindPushRegistrationController {
      override val service: PushRegistrationService = testPushRegistrationService
      val testCompositeAction = new TestAccountAccessControlWithAccept(testAccess)
      override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
      override implicit val ec: ExecutionContext = ExecutionContext.global
    }
  }

  trait LockFailed extends Setup {
    val testLockRepository = new TestLockRepository(false)
    val testFinderRepository = found
    override val testPushRegistrationService = new TestPushRegistrationService(testAccess, testFinderRepository, testLockRepository, MicroserviceAuditConnector)

    val controller = new FindPushRegistrationController {
      override val service: PushRegistrationService = testPushRegistrationService
      val testCompositeAction = new TestAccountAccessControlWithAccept(testAccess)
      override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
      override implicit val ec: ExecutionContext = ExecutionContext.global

    }
  }

  trait AuthWithoutNino extends Setup {

    override val testAccess =  new TestAccountAccessControl(None) {
      override def grantAccess()(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Authority] = Future.failed(new Upstream4xxResponse("Error", 401, 401))
    }

    val testLockRepository = new TestLockRepository
    val testFinderRepository = found

    override val testPushRegistrationService = new TestPushRegistrationService(testAccess, testFinderRepository, testLockRepository, MicroserviceAuditConnector)

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

    "return 409 conflict when the lock cannot be obtained" in new LockFailed {
      val result: Result = await(controller.findIncompleteRegistrations()(emptyRequestWithAcceptHeader))

      status(result) shouldBe 409
      jsonBodyOf(result) shouldBe Json.parse("""{"code":"CONFLICT","message":"Failed to obtain lock"}""")
    }

  }


  "findTimedOutRegistrations PushNotificationController" should {

    "find timed-out tokens and return 200 success and Json" in new Incomplete {
      val result: Result = await(controller.findTimedOutRegistrations()(emptyRequestWithAcceptHeader))

      status(result) shouldBe 200
      contentAsJson(result) shouldBe Json.toJson(Seq(foundIncompleteRegistration))

    }

    "return 404 not found when there are no timed-out tokens" in new NotFoundResult {
      val result: Result = await(controller.findTimedOutRegistrations()(emptyRequestWithAcceptHeader))

      status(result) shouldBe 404
      contentAsJson(result) shouldBe Json.parse("""{"code":"NOT_FOUND","message":"No unregistered endpoints"}""")

    }

    "return 409 service unavailable when the lock cannot be obtained" in new LockFailed {
      val result: Result = await(controller.findTimedOutRegistrations()(emptyRequestWithAcceptHeader))

      status(result) shouldBe 409
      jsonBodyOf(result) shouldBe Json.parse("""{"code":"CONFLICT","message":"Failed to obtain lock"}""")
    }

  }

  "removeStaleRegistrations PushNotificationController" should {
    "remove stale registrations and return 200 success and Json" in new Success {
      val result: Result = await(controller.removeStaleRegistrations()(emptyRequestWithAcceptHeader))

      status(result) shouldBe 200
      jsonBodyOf(result) shouldBe Json.parse("""{"removed":5}""")
    }

    "return 404 not found when there are no stale registrations" in new NotFoundResult {
      val result: Result = await(controller.removeStaleRegistrations()(emptyRequestWithAcceptHeader))

      status(result) shouldBe 404
      jsonBodyOf(result) shouldBe Json.parse("""{"code":"NOT_FOUND","message":"No stale registrations"}""")
    }
  }
}
