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

import play.api.libs.json.{JsValue, Json}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.mongo.{Updated, Saved, DatabaseUpdate}
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.auth.microservice.connectors.ConfidenceLevel
import uk.gov.hmrc.play.http.{ForbiddenException, HeaderCarrier, HttpGet, Upstream4xxResponse}
import uk.gov.hmrc.pushregistration.config.MicroserviceAuditConnector
import uk.gov.hmrc.pushregistration.connectors.{Authority, AuthConnector}
import uk.gov.hmrc.pushregistration.controllers.PushRegistrationController
import uk.gov.hmrc.pushregistration.controllers.action.{AccountAccessControlCheckAccessOff, AccountAccessControl, AccountAccessControlWithHeaderCheck}
import uk.gov.hmrc.pushregistration.domain.PushRegistration
import uk.gov.hmrc.pushregistration.repository.{PushRegistrationPersist, PushRegistrationRepository}
import uk.gov.hmrc.pushregistration.services.{SandboxPushRegistrationService, LivePushRegistrationService, PushRegistrationService}

import scala.concurrent.{ExecutionContext, Future}


class TestAuthConnector(nino: Option[Nino]) extends AuthConnector {
  override val serviceUrl: String = "someUrl"

  override def serviceConfidenceLevel: ConfidenceLevel = ???

  override def http: HttpGet = ???

  override def grantAccess()(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Authority] = Future(Authority(nino.get, ConfidenceLevel.L200, "authId"))
}

class TestRepository extends PushRegistrationRepository {
  override def save(registration: PushRegistration, authId:String): Future[DatabaseUpdate[PushRegistrationPersist]] = Future.successful(DatabaseUpdate(null, Saved(PushRegistrationPersist(BSONObjectID.generate, registration.token, authId))))

  override def findByAuthId(authId: String): Future[Seq[PushRegistrationPersist]] = ???
}

class TestPushRegistrationService(testAuthConnector:TestAuthConnector, testRepository:TestRepository, testAuditConnector: AuditConnector) extends LivePushRegistrationService {
  var saveDetails:Map[String, String]=Map.empty

  override def audit(service: String, details: Map[String, String])(implicit hc: HeaderCarrier, ec : ExecutionContext) = {
    saveDetails=details
    Future.successful(AuditResult.Success)
  }

  override val auditConnector = testAuditConnector
  override val repository = testRepository
}

class TestAccessCheck(testAuthConnector: TestAuthConnector) extends AccountAccessControl {
  override val authConnector: AuthConnector = testAuthConnector
}

class TestAccountAccessControlWithAccept(testAccessCheck:AccountAccessControl) extends AccountAccessControlWithHeaderCheck {
  override val accessControl: AccountAccessControl = testAccessCheck
}


trait Setup {
  implicit val hc = HeaderCarrier()

  val nino = Nino("CS700100A")
  val acceptHeader = "Accept" -> "application/vnd.hmrc.1.0+json"
  val emptyRequest = FakeRequest()

  val registration = PushRegistration("token")
  val registrationJsonBody: JsValue = Json.toJson(registration)

  def fakeRequest(body:JsValue) = FakeRequest(POST, "url").withBody(body)
    .withHeaders("Content-Type" -> "application/json")

  val emptyRequestWithAcceptHeader = FakeRequest().withHeaders(acceptHeader)

  lazy val registrationBadRequest = fakeRequest(Json.toJson("Something Incorrect")).withHeaders(acceptHeader)

  lazy val jsonRegistrationRequestWithNoAuthHeader = fakeRequest(registrationJsonBody).withHeaders(acceptHeader)

  lazy val jsonRegistrationRequestNoAcceptHeader = fakeRequest(registrationJsonBody)

  val authConnector = new TestAuthConnector(Some(nino))
  val testRepository = new TestRepository
  val testAccess = new TestAccessCheck(authConnector)
  val testCompositeAction = new TestAccountAccessControlWithAccept(testAccess)
  val testPushRegistrationService = new TestPushRegistrationService(authConnector, testRepository , MicroserviceAuditConnector)
  val testSandboxPersonalIncomeService = SandboxPushRegistrationService
  val sandboxCompositeAction = AccountAccessControlCheckAccessOff
}

trait Success extends Setup {
  val controller = new PushRegistrationController {
    override val service: PushRegistrationService = testPushRegistrationService
    override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
    override implicit val ec: ExecutionContext = ExecutionContext.global
  }
}

trait SuccessUpdated extends Setup {

  override val testRepository = new TestRepository {
    override def save(registration: PushRegistration, authId:String): Future[DatabaseUpdate[PushRegistrationPersist]] = {
      val update = PushRegistrationPersist(BSONObjectID.generate, registration.token, authId)
      Future.successful(DatabaseUpdate(null, Updated(update,update)))
    }
  }
  override val testPushRegistrationService = new TestPushRegistrationService(authConnector, testRepository , MicroserviceAuditConnector)

  val controller = new PushRegistrationController {
    override val service: PushRegistrationService = testPushRegistrationService
    override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
    override implicit val ec: ExecutionContext = ExecutionContext.global
  }
}

trait AuthWithoutNino extends Setup {

  override val authConnector =  new TestAuthConnector(None) {
    override def grantAccess()(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Authority] = Future.failed(new Upstream4xxResponse("Error", 401, 401))
  }

  override val testAccess = new TestAccessCheck(authConnector)
  override val testCompositeAction = new TestAccountAccessControlWithAccept(testAccess)
  override val testPushRegistrationService = new TestPushRegistrationService(authConnector, testRepository, MicroserviceAuditConnector)

  val controller = new PushRegistrationController {
    override val service: PushRegistrationService = testPushRegistrationService
    override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
    override implicit val ec: ExecutionContext = ExecutionContext.global
  }
}

trait AuthLowCL extends Setup {

  override val authConnector =  new TestAuthConnector(None) {
    override def grantAccess()(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Authority] = Future.failed(new ForbiddenException("Forbidden"))
  }

  override val testAccess = new TestAccessCheck(authConnector)
  override val testCompositeAction = new TestAccountAccessControlWithAccept(testAccess)
  override val testPushRegistrationService = new TestPushRegistrationService(authConnector, testRepository, MicroserviceAuditConnector)

  val controller = new PushRegistrationController {
    override val service: PushRegistrationService = testPushRegistrationService
    override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
    override implicit val ec: ExecutionContext = ExecutionContext.global
  }
}

trait SandboxSuccess extends Setup {
  val controller = new PushRegistrationController {
    override val service: PushRegistrationService = testSandboxPersonalIncomeService
    override val accessControl: AccountAccessControlWithHeaderCheck = sandboxCompositeAction
    override implicit val ec: ExecutionContext = ExecutionContext.global
  }
}
