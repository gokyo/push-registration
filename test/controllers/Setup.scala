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

import java.util.UUID

import org.joda.time.Duration
import play.api.libs.json.{JsValue, Json}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.modules.reactivemongo.MongoDbConnection
import reactivemongo.api.DB
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.{DatabaseUpdate, Saved, Updated}
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.auth.microservice.connectors.ConfidenceLevel
import uk.gov.hmrc.play.http.{ForbiddenException, HeaderCarrier, HttpGet, Upstream4xxResponse}
import uk.gov.hmrc.pushregistration.config.MicroserviceAuditConnector
import uk.gov.hmrc.pushregistration.connectors.{AuthConnector, Authority}
import uk.gov.hmrc.pushregistration.controllers.PushRegistrationController
import uk.gov.hmrc.pushregistration.controllers.action.{AccountAccessControl, AccountAccessControlCheckAccessOff, AccountAccessControlWithHeaderCheck}
import uk.gov.hmrc.pushregistration.domain.{Device, NativeOS, PushRegistration}
import uk.gov.hmrc.pushregistration.repository.{PushRegistrationPersist, PushRegistrationRepository}
import uk.gov.hmrc.pushregistration.services.{LivePushRegistrationService, PushRegistrationService, SandboxPushRegistrationService}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class TestAuthConnector(nino: Option[Nino]) extends AuthConnector {
  override val serviceUrl: String = "someUrl"

  override def serviceConfidenceLevel: ConfidenceLevel = throw new Exception("Not used")

  override def http: HttpGet = throw new Exception("Not used")

  override def grantAccess()(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Authority] = Future(Authority(nino.get, ConfidenceLevel.L200, "authId"))
}

class TestRepository extends PushRegistrationRepository {
  var savedRegistration: Option[PushRegistration] = None

  override def save(registration: PushRegistration, authId: String): Future[DatabaseUpdate[PushRegistrationPersist]] = {
    savedRegistration = Some(registration)
    Future.successful(DatabaseUpdate(null, Saved(PushRegistrationPersist(BSONObjectID.generate, registration.token, authId, registration.device, None))))
  }

  override def findByAuthId(authId: String): Future[Seq[PushRegistrationPersist]] = ???

  override def findIncompleteRegistrations(maxRows: Int): Future[Seq[PushRegistrationPersist]] = ???

  override def removeToken(token: String): Future[Boolean] = ???

  override def saveEndpoint(token: String, endpoint: String): Future[Boolean] = ???

  override def findTimedOutRegistrations(timeoutMilliseconds: Long, maxRows: Int): Future[Seq[PushRegistrationPersist]] = ???
}

class TestLockRepository(canLock: Boolean = true)(implicit mongo: () => DB) extends LockRepository()(mongo) {
  override def lock(reqLockId: String, reqOwner: String, forceReleaseAfter: Duration): Future[Boolean] = Future(canLock)

  override def renew(reqLockId: String, reqOwner: String, forceReleaseAfter: Duration): Future[Boolean] = Future(canLock)

  override def releaseLock(reqLockId: String, reqOwner: String): Future[Unit] = Future({})

  override def isLocked(reqLockId: String, reqOwner: String): Future[Boolean] = ???
}

class TestPushRegistrationService(testAuthConnector: TestAuthConnector, testRepository: TestRepository, testLockRepository: LockRepository, testAuditConnector: AuditConnector) extends LivePushRegistrationService {
  var saveDetails:Map[String, String]=Map.empty

  override def audit(service: String, details: Map[String, String])(implicit hc: HeaderCarrier, ec: ExecutionContext) = {
    saveDetails = details
    Future.successful(AuditResult.Success)
  }

  override val auditConnector = testAuditConnector
  override val pushRegistrationRepository = testRepository
  override val lockRepository = testLockRepository
  override val batchSize = 10
}

class TestAccessCheck(testAuthConnector: TestAuthConnector) extends AccountAccessControl {
  override val authConnector: AuthConnector = testAuthConnector
}

class TestAccountAccessControlWithAccept(testAccessCheck: AccountAccessControl) extends AccountAccessControlWithHeaderCheck {
  override val accessControl: AccountAccessControl = testAccessCheck
}


trait Setup extends MongoDbConnection {
  implicit val hc = HeaderCarrier()
  val journeyId = Option(UUID.randomUUID().toString)

  val nino = Nino("CS700100A")
  val acceptHeader = "Accept" -> "application/vnd.hmrc.1.0+json"
  val emptyRequest = FakeRequest()

  lazy val device = Device(NativeOS.Android, "1.2", "1.3", "samsung")

  lazy val registration = PushRegistration("token-a", None, None)
  lazy val registrationWithDevice = PushRegistration("token-b", Some(device), None)
  lazy val tokenToEndpointMap = Map("token-a" -> Some("/end/point"), "token-b" -> None)
  lazy val registrationJsonBody: JsValue = Json.toJson(registration)
  lazy val registrationWithDeviceJsonBody: JsValue = Json.toJson(registrationWithDevice)
  lazy val tokenToEndpointMapJsonBody: JsValue = Json.toJson(tokenToEndpointMap)

  def fakeRequest(body: JsValue) = FakeRequest(POST, "url").withBody(body)
    .withHeaders("Content-Type" -> "application/json")

  lazy val emptyRequestWithAcceptHeader = FakeRequest().withHeaders(acceptHeader)

  lazy val registrationBadRequest = fakeRequest(Json.toJson("Something Incorrect")).withHeaders(acceptHeader)
  lazy val registrationBadRequestInvalidDevice = fakeRequest(Json.toJson("""{"token":"token","device":{"os":"unknown","version":"1.1","model":"some-device"}}""")).withHeaders(acceptHeader)

  lazy val jsonRegistrationRequestTokenOnly = fakeRequest(registrationJsonBody).withHeaders(acceptHeader)
  lazy val jsonRegistrationRequestTokenAndDevice = fakeRequest(registrationWithDeviceJsonBody).withHeaders(acceptHeader)

  lazy val endpointRequest = fakeRequest(tokenToEndpointMapJsonBody).withHeaders(acceptHeader)

  def getRequest(id: Int) = if (id == 1) jsonRegistrationRequestTokenOnly else jsonRegistrationRequestTokenAndDevice

  def getRegistration(id: Int) = if (id == 1) registration else registrationWithDevice

  def buildAuditCheck(testId: Int) = {
    Map("token" -> getRegistration(testId).token) ++ getRegistration(testId).device.fold(Map[String, String]())(item => {
      Map("device" -> Json.stringify(Json.toJson(item)))
    })
  }

  lazy val jsonRegistrationRequestNoAcceptHeader = fakeRequest(registrationJsonBody)

  val authConnector = new TestAuthConnector(Some(nino))
  val testRepository = new TestRepository
  val lockRepository = new TestLockRepository
  val testAccess = new TestAccessCheck(authConnector)
  val testCompositeAction = new TestAccountAccessControlWithAccept(testAccess)
  val testPushRegistrationService = new TestPushRegistrationService(authConnector, testRepository, lockRepository, MicroserviceAuditConnector)
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
    override def save(registration: PushRegistration, authId: String): Future[DatabaseUpdate[PushRegistrationPersist]] = {
      val update = PushRegistrationPersist(BSONObjectID.generate, registration.token, authId, None, None)
      savedRegistration = Some(registration)
      Future.successful(DatabaseUpdate(null, Updated(update, update)))
    }
  }
  override val testPushRegistrationService = new TestPushRegistrationService(authConnector, testRepository, lockRepository, MicroserviceAuditConnector)

  val controller = new PushRegistrationController {
    override val service: PushRegistrationService = testPushRegistrationService
    override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
    override implicit val ec: ExecutionContext = ExecutionContext.global
  }
}

trait DbaseFailure extends Setup {

  override val testRepository = new TestRepository {
    override def save(registration: PushRegistration, authId: String): Future[DatabaseUpdate[PushRegistrationPersist]] = {
      Future.failed(new Exception("controlled repository explosion!"))
    }
  }
  override val testPushRegistrationService = new TestPushRegistrationService(authConnector, testRepository, lockRepository, MicroserviceAuditConnector)

  val controller = new PushRegistrationController {
    override val service: PushRegistrationService = testPushRegistrationService
    override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
    override implicit val ec: ExecutionContext = ExecutionContext.global
  }
}


trait AuthWithoutNino extends Setup {

  override val authConnector = new TestAuthConnector(None) {
    override def grantAccess()(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Authority] = Future.failed(new Upstream4xxResponse("Error", 401, 401))
  }

  override val testAccess = new TestAccessCheck(authConnector)
  override val testCompositeAction = new TestAccountAccessControlWithAccept(testAccess)
  override val testPushRegistrationService = new TestPushRegistrationService(authConnector, testRepository, lockRepository, MicroserviceAuditConnector)

  val controller = new PushRegistrationController {
    override val service: PushRegistrationService = testPushRegistrationService
    override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
    override implicit val ec: ExecutionContext = ExecutionContext.global
  }
}

trait AuthLowCL extends Setup {

  override val authConnector = new TestAuthConnector(None) {
    override def grantAccess()(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Authority] = Future.failed(new ForbiddenException("Forbidden"))
  }

  override val testAccess = new TestAccessCheck(authConnector)
  override val testCompositeAction = new TestAccountAccessControlWithAccept(testAccess)
  override val testPushRegistrationService = new TestPushRegistrationService(authConnector, testRepository, lockRepository, MicroserviceAuditConnector)

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
