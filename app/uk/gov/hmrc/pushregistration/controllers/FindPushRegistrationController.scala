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

import play.api.libs.json._
import play.api.mvc.Action
import uk.gov.hmrc.api.controllers.HeaderValidator
import uk.gov.hmrc.play.HeaderCarrierConverter
import uk.gov.hmrc.play.microservice.controller.BaseController
import uk.gov.hmrc.pushregistration.controllers.action.AccountAccessControlWithHeaderCheck
import uk.gov.hmrc.pushregistration.domain.PushRegistration
import uk.gov.hmrc.pushregistration.services.{LivePushRegistrationService, PushRegistrationService}

import scala.concurrent.{ExecutionContext, Future}

trait FindPushRegistrationController extends BaseController with HeaderValidator with ErrorHandling {
  val NoUnregisteredEndpoints: JsValue = Json.parse("""{"code":"NOT_FOUND","message":"No unregistered endpoints"}""")
  val NoStaleRegistrations: JsValue = Json.parse("""{"code":"NOT_FOUND","message":"No stale registrations"}""")
  val LockFailed: JsValue = Json.parse("""{"code":"CONFLICT","message":"Failed to obtain lock"}""")

  val service: PushRegistrationService
  val accessControl: AccountAccessControlWithHeaderCheck

  def find(id: String, journeyId: Option[String] = None) = accessControl.validateAccept(acceptHeaderValidationRules).async {
    implicit request =>
      implicit val hc = HeaderCarrierConverter.fromHeadersAndSession(request.headers, None)
      errorWrapper(service.find(id).map {
        response =>
          if (response.isEmpty) NotFound else Ok(Json.toJson(response))
      })
  }

  def removeStaleRegistrations() = Action.async {
    implicit request =>
      implicit val hc = HeaderCarrierConverter.fromHeadersAndSession(request.headers, None)
      errorWrapper(service.removeStaleRegistrations.map {
        count =>
          if (count == 0) NotFound(NoStaleRegistrations) else Ok(Json.parse(s"""{"removed":$count}"""))
      })
  }

  def findIncompleteRegistrations() = findRegistrations(service.findIncompleteRegistrations())

  def findTimedOutRegistrations() = findRegistrations(service.findTimedOutRegistrations())

  private def findRegistrations(f: => Future[Option[Seq[PushRegistration]]]) = Action.async {
    implicit request =>
      implicit val hc = HeaderCarrierConverter.fromHeadersAndSession(request.headers, None)
      errorWrapper(f.map {
        _.map {
          registrations =>
            if (registrations.isEmpty) NotFound(NoUnregisteredEndpoints) else Ok(Json.toJson(registrations))
        }.getOrElse(Conflict(LockFailed))
      })
  }
}

object FindPushRegistrationController extends FindPushRegistrationController {
  override val service = LivePushRegistrationService
  override val accessControl = AccountAccessControlWithHeaderCheck
  override implicit val ec: ExecutionContext = ExecutionContext.global
}
