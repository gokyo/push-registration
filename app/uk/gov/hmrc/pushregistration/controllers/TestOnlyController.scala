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

import play.api.mvc.Action
import play.api.libs.json._
import uk.gov.hmrc.pushregistration.repository.{PushRegistrationMongoRepositoryTests, PushRegistrationRepositoryTest}
import uk.gov.hmrc.play.microservice.controller.BaseController
import uk.gov.hmrc.api.controllers._
import scala.concurrent.ExecutionContext

trait TestOnlyController extends BaseController with HeaderValidator with ErrorHandling {
  val pushRegistrationRepositoryTest: PushRegistrationMongoRepositoryTests
  def dropMongo() = Action.async {
    pushRegistrationRepositoryTest.removeAllRecords().map ( _ => Ok)
  }

  def findByEndpointAndToken(authId:String, token:String) = Action.async {
    val notFound = NotFound("No endpoint found!")
    pushRegistrationRepositoryTest.findByAuthIdAndToken(authId, token).map {
      case Some(res) if res.endpoint.isDefined => Ok(Json.parse(s"""{"endpoint":"${res.endpoint.get}"}"""))

      case _ => notFound
    }
  }
}

object TestOnlyController extends TestOnlyController {
  override val pushRegistrationRepositoryTest = PushRegistrationRepositoryTest()

  override implicit val ec: ExecutionContext = ExecutionContext.global
}
