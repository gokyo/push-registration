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

package uk.gov.hmrc.pushregistration.controllers

import play.api.Logger
import play.api.libs.json._
import play.api.mvc.BodyParsers
import uk.gov.hmrc.api.controllers._
import uk.gov.hmrc.http.UnauthorizedException
import uk.gov.hmrc.play.HeaderCarrierConverter
import uk.gov.hmrc.play.microservice.controller.BaseController
import uk.gov.hmrc.pushregistration.controllers.action.{AccountAccessControlCheckAccessOff, AccountAccessControlWithHeaderCheck}
import uk.gov.hmrc.pushregistration.domain.PushRegistration
import uk.gov.hmrc.pushregistration.services._

import scala.concurrent.{ExecutionContext, Future}


trait PushRegistrationController extends BaseController with HeaderValidator with ErrorHandling {
  val service: PushRegistrationService
  val accessControl: AccountAccessControlWithHeaderCheck

  final def register(journeyId: Option[String] = None) = accessControl.validateAccept(acceptHeaderValidationRules).async(BodyParsers.parse.json) {
    implicit authenticated =>
      implicit val hc = HeaderCarrierConverter.fromHeadersAndSession(authenticated.request.headers, None)

      authenticated.request.body.validate[PushRegistration].fold(
        errors => {
          val failure = JsError.toJson(errors)
          Logger.warn("Received error with parsing service register: " + failure)
          Future.successful(BadRequest(Json.obj("message" -> failure)))
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
  override val accessControl = AccountAccessControlCheckAccessOff
  override implicit val ec: ExecutionContext = ExecutionContext.global
}

object LivePushRegistrationController extends PushRegistrationController {
  override val service = LivePushRegistrationService
  override val accessControl = AccountAccessControlWithHeaderCheck
  override implicit val ec: ExecutionContext = ExecutionContext.global
}
