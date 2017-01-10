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

package repository

import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.{BeforeAndAfterEach, LoneElement}
import reactivemongo.bson.BSONObjectID
import reactivemongo.core.errors.DatabaseException
import uk.gov.hmrc.mongo.{DatabaseUpdate, MongoSpecSupport, Saved, Updated}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.pushregistration.domain.{NativeOS, Device, PushRegistration}
import uk.gov.hmrc.pushregistration.repository.{PushRegistrationPersist, PushRegistrationMongoRepository}
import scala.concurrent.ExecutionContext.Implicits.global

class PushRegistrationMongoRepositorySpec extends UnitSpec with
                                                 MongoSpecSupport with
                                                 BeforeAndAfterEach with
                                                 ScalaFutures with
                                                 LoneElement with
                                                 Eventually {

  private val repository: PushRegistrationMongoRepository = new PushRegistrationMongoRepository

  trait Setup {
    val authId = "some-auth-id"
    val testToken1 = "token-1"
    val testToken2 = "token-2"
    val testToken3 = "token-3"
    val testToken4 = "token-4"
    val deviceAndroid = Device(NativeOS.Android, "7.0", "1.1", "nexus")
    val deviceiOS = Device(NativeOS.iOS, "2.3", "1.2", "apple")
    val deviceWindows = Device(NativeOS.Windows, "3.3", "1.2", "some-windows-device")
    val registration = PushRegistration(testToken1, None)
    val registrationWithDeviceAndroid = PushRegistration(testToken2, Some(deviceAndroid))
    val registrationWithDeviceiOS = PushRegistration(testToken3, Some(deviceiOS))
    val registrationWithDeviceWindows = PushRegistration(testToken4, Some(deviceWindows))
    val items = Seq(
        (registration, "auth-1"),
        (registrationWithDeviceAndroid, "auth-2"),
        (registrationWithDeviceiOS, "auth-3"),
        (registrationWithDeviceWindows, "auth-4")
    )
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    await(repository.drop)
    await(repository.ensureIndexes)
  }

  "Validating index's " should {

    "able to insert duplicate data entries for auth Id and Token" in new Setup {
      val resp: DatabaseUpdate[PushRegistrationPersist] = await(repository.save(registration, authId))

      a[DatabaseException] should be thrownBy await(repository.insert(resp.updateType.savedValue))
      await(repository.insert(resp.updateType.savedValue.copy(id = BSONObjectID.generate)))
      await(repository.insert(resp.updateType.savedValue.copy(id = BSONObjectID.generate, authId = "another authId")))
    }
  }

  "repository" should {

    "create multiple records if the token is different" in new Setup {

      items.map( item => {
        val result = await(repository.save(item._1, item._2))

        result.updateType shouldBe an[Saved[_]]
        result.updateType.savedValue.token shouldBe item._1.token
        result.updateType.savedValue.authId shouldBe item._2
        result.updateType.savedValue.device shouldBe item._1.device

        val result2 = await(repository.save(item._1.copy(token = item._1.token + "another token"), item._2))
        result2.updateType shouldBe an[Saved[_]]
        result2.updateType.savedValue.token shouldBe item._1.token + "another token"
        result2.updateType.savedValue.authId shouldBe item._2
        result2.updateType.savedValue.device shouldBe item._1.device
      })
    }

    "insert multiple unique tokens for an authId and update when tokens already exist" in new Setup {

      items.foreach( item => {
        val result = await(repository.save(item._1, item._2))

        result.updateType shouldBe an[Saved[_]]
        result.updateType.savedValue.token shouldBe item._1.token
        result.updateType.savedValue.authId shouldBe item._2

        val result1 = await(repository.save(item._1.copy(token = item._1.token + "another token"), item._2))
        result1.updateType shouldBe an[Saved[_]]
        val result2 =await(repository.save(item._1.copy(token = item._1.token + "yet another token"), item._2))
        result2.updateType shouldBe an[Saved[_]]
        val result3 =await(repository.save(item._1.copy(token = item._1.token + "yet another token"), item._2))
        result3.updateType shouldBe an[Updated[_]]

        val findResult = await(repository.findByAuthId(item._2))
        findResult.size shouldBe 3

        findResult(2).token shouldBe item._1.token
        findResult(2).authId shouldBe item._2
        findResult(2).device shouldBe item._1.device

        findResult(1).token shouldBe item._1.token + "another token"
        findResult(1).authId shouldBe item._2
        findResult(1).device shouldBe item._1.device

        findResult.head.token shouldBe item._1.token + "yet another token"
        findResult.head.authId shouldBe item._2
        findResult.head.device shouldBe item._1.device
      })
    }

    "find an existing record" in new Setup {
      val result = await(repository.save(registrationWithDeviceAndroid, authId))

      result.updateType shouldBe an[Saved[_]]
      result.updateType.savedValue.token shouldBe testToken2
      result.updateType.savedValue.authId shouldBe authId

      val findResult: Seq[PushRegistrationPersist] = await(repository.findByAuthId(authId))
      findResult.head.token shouldBe testToken2
      findResult.head.authId shouldBe authId
      findResult.head.device shouldBe Some(deviceAndroid)
    }

    "return no records when an unknown authId is supplied" in new Setup {
      val result = await(repository.save(registration, authId))

      val findResult = await(repository.findByAuthId("unknown"))
      findResult shouldBe List.empty
    }
  }
}
