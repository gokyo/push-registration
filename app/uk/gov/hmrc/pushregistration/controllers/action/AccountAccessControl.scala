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

package uk.gov.hmrc.pushregistration.controllers.action

import play.api.Logger
import play.api.Play.{configuration, current}
import play.api.libs.json.Json.toJson
import play.api.mvc._
import uk.gov.hmrc.api.controllers.{ErrorAcceptHeaderInvalid, ErrorUnauthorized, ErrorUnauthorizedLowCL, HeaderValidator}
import uk.gov.hmrc.auth.core.retrieve.Retrievals._
import uk.gov.hmrc.auth.core.retrieve.~
import uk.gov.hmrc.auth.core.{AuthConnector, AuthorisedFunctions, ConfidenceLevel}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.{HttpException, Upstream4xxResponse, Request => _, _}
import uk.gov.hmrc.play.HeaderCarrierConverter
import uk.gov.hmrc.play.config.ServicesConfig
import uk.gov.hmrc.pushregistration.config.MicroserviceAuthConnector
import uk.gov.hmrc.pushregistration.controllers.{ErrorUnauthorizedNoNino, ForbiddenAccess}

import scala.concurrent.{ExecutionContext, Future}

case class AuthenticatedRequest[A](authority: Option[Authority], request: Request[A]) extends WrappedRequest(request)

case class Authority(nino: Nino, cl: ConfidenceLevel, authInternalId: String)

class NinoNotFoundOnAccount(message: String) extends HttpException(message, 401)

class NoInternalId(message: String) extends HttpException(message, 401)

class AccountWithLowCL(message: String) extends HttpException(message, 401)

trait AccountAccessControl extends ActionBuilder[AuthenticatedRequest] with Results with AuthorisedFunctions {

  import scala.concurrent.ExecutionContext.Implicits.global

  val authConnector: AuthConnector = MicroserviceAuthConnector

  def serviceConfidenceLevel: ConfidenceLevel

  def invokeBlock[A](request: Request[A], block: AuthenticatedRequest[A] => Future[Result]) = {
    implicit val hc = HeaderCarrierConverter.fromHeadersAndSession(request.headers, None)

    grantAccess().flatMap {
      authority => {
        block(AuthenticatedRequest(Some(authority),request))
      }
    }.recover {
      case ex:Upstream4xxResponse => Unauthorized(toJson(ErrorUnauthorized))

      case ex:ForbiddenException =>
        Logger.info("Unauthorized! ForbiddenException caught and returning 403 status!")
        Forbidden(toJson(ForbiddenAccess))

      case ex:NinoNotFoundOnAccount =>
        Logger.info("Unauthorized! NINO not found on account!")
        Unauthorized(toJson(ErrorUnauthorizedNoNino))

      case ex:AccountWithLowCL =>
        Logger.info("Unauthorized! Account with low CL!")
        Unauthorized(toJson(ErrorUnauthorizedLowCL))
    }
  }

  def grantAccess()(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Authority] = {
    authorised().retrieve(nino and confidenceLevel and internalId) {
      case Some(foundNino) ~ foundConfidenceLevel ~ Some(foundInternalId) ⇒ {
        if (serviceConfidenceLevel.level > foundConfidenceLevel.level)
          throw new ForbiddenException("The user does not have sufficient permissions to access this service")
        else Future(Authority(Nino(foundNino), foundConfidenceLevel, foundInternalId))
      } case None ~ _ ~ _ ⇒ {
        throw throw new NinoNotFoundOnAccount("The user must have a National Insurance Number")
      } case Some(_) ~ _ ~ None ⇒ {
        throw throw new NoInternalId("The user must have an internal id")
      }
    }
  }
}

trait AccountAccessControlWithHeaderCheck extends HeaderValidator {
  val checkAccess=true
  val accessControl:AccountAccessControl

  override def validateAccept(rules: Option[String] => Boolean) = new ActionBuilder[AuthenticatedRequest] {

    def invokeBlock[A](request: Request[A], block: AuthenticatedRequest[A] => Future[Result]) = {
      if (rules(request.headers.get("Accept"))) {
        if (checkAccess) accessControl.invokeBlock(request, block)
        else block(AuthenticatedRequest(None,request))
      }
      else Future.successful(Status(ErrorAcceptHeaderInvalid.httpStatusCode)(toJson(ErrorAcceptHeaderInvalid)))
    }
  }
}

object AccountAccessControl extends AccountAccessControl with ServicesConfig{
  private lazy val configureConfidenceLevel: Int = configuration.getInt("controllers.confidenceLevel").getOrElse(
    throw new RuntimeException("The service has not been configured with a confidence level"))
  private lazy val confidenceLevel = ConfidenceLevel.fromInt(configureConfidenceLevel).getOrElse(
    throw new RuntimeException(s"unknown confidence level found: $configureConfidenceLevel"))

  override def serviceConfidenceLevel: ConfidenceLevel = confidenceLevel
}

object AccountAccessControlWithHeaderCheck extends AccountAccessControlWithHeaderCheck {
  val accessControl: AccountAccessControl = AccountAccessControl
}

object AccountAccessControlSandbox extends AccountAccessControl {
  override def serviceConfidenceLevel: ConfidenceLevel = ConfidenceLevel.L0
}

object AccountAccessControlCheckAccessOff extends AccountAccessControlWithHeaderCheck {
  override val checkAccess=false

  val accessControl: AccountAccessControl = AccountAccessControlSandbox
}
