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

import play.api.libs.json.Json
import play.api.mvc.Action
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.microservice.controller.BaseController
import uk.gov.hmrc.pushregistration.services.{LivePushRegistrationService, PushRegistrationService}

import scala.concurrent.ExecutionContext.Implicits.global

trait FindPushRegistrationController extends BaseController {
  val service: PushRegistrationService

  def find(id:String) = Action.async {

    implicit request =>
      implicit val hc = HeaderCarrier.fromHeadersAndSession(request.headers, None)

      service.find(id).map {
        case Some(found) => Ok(Json.toJson(found))
        case _ => NotFound
      }
  }
}

// Note: Controller is not exposed to the API gateway.
object FindPushRegistrationController extends FindPushRegistrationController {
  override val service = LivePushRegistrationService
}
