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

package repository

import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.{BeforeAndAfterEach, LoneElement}
import reactivemongo.bson.BSONObjectID
import reactivemongo.core.errors.DatabaseException
import uk.gov.hmrc.mongo.{DatabaseUpdate, MongoSpecSupport, Saved, Updated}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.pushregistration.domain.PushRegistration
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
    val resultCodeUpdate = 500
    val testId = "12345"
    val testToken = "token"
    val registration = PushRegistration(testId, testToken)
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    await(repository.drop)
    await(repository.ensureIndexes)
  }

  "Validating index's " should {

    "not be able to insert duplicate data entries" in new Setup {
      val resp: DatabaseUpdate[PushRegistrationPersist] = await(repository.save(registration, authId))

      a[DatabaseException] should be thrownBy await(repository.insert(resp.updateType.savedValue))
      a[DatabaseException] should be thrownBy await(repository.insert(resp.updateType.savedValue.copy(id = BSONObjectID.generate)))
      a[DatabaseException] should be thrownBy await(repository.insert(resp.updateType.savedValue.copy(id = BSONObjectID.generate, authId = "another authId")))
    }
  }

  "repository" should {

    "create a new record" in new Setup {
      val result = await(repository.save(registration, authId))
      result.updateType shouldBe an[Saved[_]]
      result.updateType.savedValue.deviceId shouldBe testId
      result.updateType.savedValue.token shouldBe testToken
      result.updateType.savedValue.authId shouldBe authId
    }

    "update an existing record with a new token" in new Setup {
      val result = await(repository.save(registration, authId))

      result.updateType shouldBe an[Saved[_]]
      result.updateType.savedValue.deviceId shouldBe testId
      result.updateType.savedValue.token shouldBe testToken
      result.updateType.savedValue.authId shouldBe authId

      val result2 = await(repository.save(registration.copy(token = "another token"), authId))
      result2.updateType shouldBe an[Updated[_]]
      result2.updateType.savedValue.deviceId shouldBe testId
      result2.updateType.savedValue.token shouldBe "another token"
      result2.updateType.savedValue.authId shouldBe authId
    }

    "create multiple records if the device Id is different" in new Setup {
      val result = await(repository.save(registration, authId))

      result.updateType shouldBe an[Saved[_]]
      result.updateType.savedValue.deviceId shouldBe testId
      result.updateType.savedValue.token shouldBe testToken
      result.updateType.savedValue.authId shouldBe authId

      val result2 = await(repository.save(registration.copy(deviceId = "another deviceId"), authId))
      result2.updateType shouldBe an[Saved[_]]
      result2.updateType.savedValue.deviceId shouldBe "another deviceId"
      result2.updateType.savedValue.token shouldBe testToken
      result2.updateType.savedValue.authId shouldBe authId
    }

    "find an existing record" in new Setup {
      val result = await(repository.save(registration, authId))

      result.updateType shouldBe an[Saved[_]]
      result.updateType.savedValue.deviceId shouldBe testId
      result.updateType.savedValue.token shouldBe testToken
      result.updateType.savedValue.authId shouldBe authId

      val findResult = await(repository.findByAuthId(authId))
      findResult.get.deviceId shouldBe testId
      findResult.get.token shouldBe testToken
      findResult.get.authId shouldBe authId
    }

    "find the latest record associated with the authId" in new Setup {
      val result = await(repository.save(registration, authId))

      result.updateType shouldBe an[Saved[_]]
      result.updateType.savedValue.deviceId shouldBe testId
      result.updateType.savedValue.token shouldBe testToken
      result.updateType.savedValue.authId shouldBe authId
      
      await(repository.save(registration.copy(deviceId = "id_1", token = "another token"), authId))
      await(repository.save(registration.copy(deviceId = "id_2", token = "yet another token"), authId))
      await(repository.save(registration.copy(token = "yet another token"), authId))

      val findResult = await(repository.findByAuthId(authId))
      findResult.get.deviceId shouldBe testId
      findResult.get.token shouldBe "yet another token"
      findResult.get.authId shouldBe authId
    }
    
    "not find an existing record" in new Setup {
      val result = await(repository.save(registration, authId))

      val findResult = await(repository.findByAuthId("unknown"))
      findResult shouldBe None
    }

  }
}
