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

package uk.gov.hmrc.pushregistration.controllers

import play.api.mvc.BodyParsers
import uk.gov.hmrc.pushregistration.controllers.action.{AccountAccessControlWithHeaderCheck, AccountAccessControlForSandbox}
import play.api.libs.json.{JsError, Json}
import uk.gov.hmrc.pushregistration.domain.PushRegistration
import uk.gov.hmrc.pushregistration.services._
import play.api.{mvc, Logger}
import uk.gov.hmrc.play.http.{UnauthorizedException, HeaderCarrier, NotFoundException}
import uk.gov.hmrc.play.microservice.controller.BaseController
import uk.gov.hmrc.api.controllers._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait ErrorHandling {
  self: BaseController =>

  def errorWrapper(func: => Future[mvc.Result])(implicit hc: HeaderCarrier) = {
    func.recover {
      case ex: NotFoundException => Status(ErrorNotFound.httpStatusCode)(Json.toJson(ErrorNotFound))

      case e: Throwable =>
        Logger.error(s"Internal server error: ${e.getMessage}", e)
        Status(ErrorInternalServerError.httpStatusCode)(Json.toJson(ErrorInternalServerError))
    }
  }
}

trait PushRegistrationController extends BaseController with HeaderValidator with ErrorHandling {
  val service: PushRegistrationService
  val accessControl: AccountAccessControlWithHeaderCheck

  final def register() = accessControl.validateAccept(acceptHeaderValidationRules).async(BodyParsers.parse.json) {
    implicit authenticated =>
      implicit val hc = HeaderCarrier.fromHeadersAndSession(authenticated.request.headers, None)

      authenticated.request.body.validate[PushRegistration].fold(
        errors => {
          Logger.warn("Received error with parsing service register: " + errors)
          Future.successful(BadRequest(Json.obj("message" -> JsError.toFlatJson(errors))))
        },
        deviceRegistration => {
          errorWrapper(
            service.register(deviceRegistration)(hc, authenticated.authority).map {
              case true => Created
              case false => Ok
            }.recover {
              case e:UnauthorizedException =>
                Logger.error("Failed to find authority context!")
                Status(ErrorUnauthorized.httpStatusCode)(Json.toJson(ErrorUnauthorized))
            }
          )
        })
    }
}

object SandboxPushRegistrationController extends PushRegistrationController {
  override val service = SandboxPushRegistrationService
  override val accessControl = AccountAccessControlForSandbox
}

object LivePushRegistrationController extends PushRegistrationController {
  override val service = LivePushRegistrationService
  override val accessControl = AccountAccessControlWithHeaderCheck
}


