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

package domain

import controllers._
import org.scalatest.concurrent.ScalaFutures
import play.api.libs.json.Json
import play.api.test.FakeApplication
import uk.gov.hmrc.play.test.{WithFakeApplication, UnitSpec}
import uk.gov.hmrc.pushregistration.domain.{OS, NativeOS, Device, PushRegistration}
import uk.gov.hmrc.pushregistration.repository.DeviceStore


case class NativeOSTest(id:Int, nativeOS:NativeOS)

class JsonTestSpec extends UnitSpec with WithFakeApplication with ScalaFutures with StubApplicationConfiguration {

  override lazy val fakeApplication = FakeApplication(additionalConfiguration = config)

  Seq(NativeOSTest(OS.iOS ,NativeOS.iOS), NativeOSTest(OS.Android ,NativeOS.Android), NativeOSTest(OS.Windows ,NativeOS.Windows)).foreach { item => {

    "JSON serialization/deserialize" should {

      s"Successfully serialize/deserialize PushRegistration for OS range $item" in {
        val pushRegObj = PushRegistration("token", Some(Device(item.nativeOS, "1.2", "1.1","some-device")), None)

        Json.toJson(pushRegObj) shouldBe Json.parse(s"""{"token":"token","device":{"os":"${item.nativeOS}","osVersion":"1.2","appVersion":"1.1","model":"some-device"}}""")
      }

      s"Successfully serialize/deserialize Device for OS range $item" in {
        val deviceJson = s"""{"os":${item.id},"osVersion":"1.1","appVersion":"1.3","model":"some-device"}"""

        val deviceJsValue = Json.parse(deviceJson).as[Device](DeviceStore.formats)
        deviceJsValue shouldBe Device(item.nativeOS, "1.1", "1.3", "some-device")


        val deviceJson1 = s"""{"os":"${item.nativeOS}","osVersion":"0.1","appVersion":"1.1","model":"some-device"}"""

        val deviceJsValue1 = Json.parse(deviceJson1).as[Device]
        deviceJsValue1 shouldBe Device(item.nativeOS, "0.1", "1.1", "some-device")
      }
    }
  }}

  "JSON serialize " should {

    s"fail to create PushRegistration when OS is unknown" in {

      a[Exception] should be thrownBy Json.parse( s"""{"token":"token","device":{"os":"unknown","osVersion":"0.1","appVersion":"1.1","model":"some-device"}}""").asOpt[PushRegistration]
    }
  }
}
