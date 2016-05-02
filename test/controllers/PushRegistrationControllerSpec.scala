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

import org.scalatest.concurrent.ScalaFutures
import play.api.mvc.Result
import play.api.test.FakeApplication
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}


class PushRegistrationControllerSpec extends UnitSpec with WithFakeApplication with ScalaFutures with StubApplicationConfiguration {

  override lazy val fakeApplication = FakeApplication(additionalConfiguration = config)

  "push registeration Live" should {

    "return successfully with a 201 response" in new Success {

      val result: Result = await(controller.register()(jsonRegistrationRequestWithNoAuthHeader))

      status(result) shouldBe 201

      testPushRegistrationService.saveDetails shouldBe Map("deviceId" -> registration.deviceId, "token" -> registration.token)
    }

    "return successfully with a 200 response" in new SuccessUpdated {

      val result: Result = await(controller.register()(jsonRegistrationRequestWithNoAuthHeader))

      status(result) shouldBe 200

      testPushRegistrationService.saveDetails shouldBe Map("deviceId" -> registration.deviceId, "token" -> registration.token)
    }

    "return bad result request when invalid json is submitted" in new Success {
      val result = await(controller.register()(registrationBadRequest))

      status(result) shouldBe 400
    }

    "Return 401 result when authority record does not contain a NINO" in new AuthWithoutNino {
      val result = await(controller.register()(jsonRegistrationRequestWithNoAuthHeader))

      status(result) shouldBe 401
    }

    "return 406 result when the headers are invalid" in new Success {
      val result: Result = await(controller.register()(jsonRegistrationRequestNoAcceptHeader))

      status(result) shouldBe 406
    }
  }

  "register push Sandbox" should {

    "return successfully with a 201 response" in new SandboxSuccess {

      val result: Result = await(controller.register()(jsonRegistrationRequestWithNoAuthHeader))

      status(result) shouldBe 201

      testPushRegistrationService.saveDetails shouldBe Map.empty
    }
  }

}
