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

package uk.gov.hmrc.pushregistration.controllers

import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{Action, BodyParsers}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.microservice.controller.BaseController
import uk.gov.hmrc.pushregistration.services.{LivePushRegistrationService, PushRegistrationService}

import scala.concurrent.{ExecutionContext, Future}

trait EndpointController extends BaseController with ErrorHandling {
  val service: PushRegistrationService

  implicit lazy val hc = HeaderCarrier()

  implicit object OptionStringReads extends Reads[Option[String]] {
    def reads(json: JsValue) = json match {
      case JsString(s) => JsSuccess(Option(s))
      case JsNull => JsSuccess(None)
      case _ => JsError("expected Option[String]")
    }
  }

  def registerEndpoints() = Action.async(BodyParsers.parse.json) {
    implicit request =>

      request.body.validate[Map[String, Option[String]]].fold(
        errors => {
          Logger.warn("Received error with service registerEndpoints: " + errors)
          Future.successful(BadRequest)
        },
        tokenToEndpointMap => {
          errorWrapper(service.registerEndpoints(tokenToEndpointMap).map {
            case true => Ok
            case _ => Accepted
          })
        }
      )
  }

  def getEndpointsWithNativeOsForAuthId(id: String) = Action.async {
    errorWrapper(

      service.find(id)
        .map(_.filter(registration => registration.endpoint.isDefined && registration.device.isDefined))
        .map(_.map(registration => registration.endpoint.get -> registration.device.get.os))
        .map(response => if (response.isEmpty) NotFound else Ok(Json.toJson(response.toMap)))
    )
  }
}

object EndpointController extends EndpointController {
  override val service = LivePushRegistrationService
  override implicit val ec: ExecutionContext = ExecutionContext.global
}
