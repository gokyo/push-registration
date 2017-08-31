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
import play.api.libs.json.{JsObject, JsUndefined, Json}
import reactivemongo.api.ReadPreference
import reactivemongo.bson.BSONObjectID
import reactivemongo.core.errors.{DatabaseException, ReactiveMongoException}
import reactivemongo.play.json._
import uk.gov.hmrc.mongo.{DatabaseUpdate, MongoSpecSupport, Saved, Updated}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.pushregistration.domain.NativeOS.{Android, Windows, iOS}
import uk.gov.hmrc.pushregistration.domain.{Device, NativeOS, PushRegistration}
import uk.gov.hmrc.pushregistration.repository.{PushRegistrationMongoRepository, PushRegistrationPersist}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class PushRegistrationMongoRepositorySpec extends UnitSpec with
  MongoSpecSupport with
  BeforeAndAfterEach with
  ScalaFutures with
  LoneElement with
  Eventually {

  private val repository: PushRegistrationMongoRepository = new PushRegistrationMongoRepository

  trait Setup {
    val allPlatforms = Seq(Android, iOS, Windows)
    val somePlatforms = Seq(Android, iOS)
    val maxRows = 10
    val authId = "some-auth-id"
    val testToken1 = "token-1"
    val testToken2 = "token-2"
    val testToken3 = "token-3"
    val testToken4 = "token-4"
    val testEndpoint = "/foo/bar/baz"
    val deviceAndroid = Device(Android, "7.0", "1.1", "nexus")
    val deviceiOS = Device(iOS, "2.3", "1.2", "apple")
    val deviceWindows = Device(Windows, "3.3", "1.2", "some-windows-device")
    val registrationUnknownDevice = PushRegistration(testToken1, None, None)
    val registrationWithDeviceAndroid = PushRegistration(testToken2, Some(deviceAndroid), None)
    val registrationWithDeviceiOS = PushRegistration(testToken3, Some(deviceiOS), None)
    val registrationWithDeviceWindows = PushRegistration(testToken4, Some(deviceWindows), None)
    val items: Seq[(PushRegistration, String)] = Seq(
      (registrationUnknownDevice, "auth-1"),
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
      val resp: DatabaseUpdate[PushRegistrationPersist] = await(repository.save(registrationUnknownDevice, authId))

      a[DatabaseException] should be thrownBy await(repository.insert(resp.updateType.savedValue))
      await(repository.insert(resp.updateType.savedValue.copy(id = BSONObjectID.generate, endpoint = None)))
      await(repository.insert(resp.updateType.savedValue.copy(id = BSONObjectID.generate, authId = "another authId", endpoint = None)))
    }
  }

  "repository" should {

    "create multiple records if the token is different" in new Setup {

      items.map(item => {
        val result = await(repository.save(item._1, item._2))

        result.updateType shouldBe an[Saved[_]]
        result.updateType.savedValue.token shouldBe item._1.token
        result.updateType.savedValue.authId shouldBe item._2
        result.updateType.savedValue.device shouldBe item._1.device

        val result2 = await(repository.save(item._1.copy(token = item._1.token + "another token", endpoint = None), item._2))
        result2.updateType shouldBe an[Saved[_]]
        result2.updateType.savedValue.token shouldBe item._1.token + "another token"
        result2.updateType.savedValue.authId shouldBe item._2
        result2.updateType.savedValue.device shouldBe item._1.device
      })
    }

    "insert multiple unique tokens for an authId and update when tokens already exist" in new Setup {

      items.foreach(item => {
        val result = await(repository.save(item._1, item._2))

        result.updateType shouldBe an[Saved[_]]
        result.updateType.savedValue.token shouldBe item._1.token
        result.updateType.savedValue.authId shouldBe item._2

        val result1 = await(repository.save(item._1.copy(token = item._1.token + "another token", endpoint = None), item._2))
        result1.updateType shouldBe an[Saved[_]]
        val result2 = await(repository.save(item._1.copy(token = item._1.token + "yet another token", endpoint = None), item._2))
        result2.updateType shouldBe an[Saved[_]]
        val result3 = await(repository.save(item._1.copy(token = item._1.token + "yet another token", endpoint = None), item._2))
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
      val result = await(repository.save(registrationUnknownDevice, authId))

      val findResult = await(repository.findByAuthId("unknown"))
      findResult shouldBe List.empty
    }

    "save endpoints associated with a token" in new Setup {
      val someAuthId = "auth-b"
      val otherAuthId = "auth-a"
      val someEndpoint = "/endpoint/b"
      val otherEndpoint = "/endpoint/c"

      await(repository.save(registrationWithDeviceAndroid, someAuthId))
      await(repository.save(registrationWithDeviceiOS, otherAuthId))

      val updatedOk: Boolean = await(repository.saveEndpoint(registrationWithDeviceAndroid.token, someEndpoint))
      val updateNotFound: Boolean = await(repository.saveEndpoint(registrationWithDeviceWindows.token, otherEndpoint))

      updatedOk shouldBe true
      updateNotFound shouldBe false

      val found = await(repository.findByAuthId(someAuthId))
      val otherFound = await(repository.findByAuthId(otherAuthId))

      found.head.endpoint shouldBe Some(someEndpoint)
      otherFound.head.endpoint shouldBe None

      val complete = await(repository.collection.
        find(Json.obj("authId" -> someAuthId)).
        cursor[JsObject](ReadPreference.primary).headOption).getOrElse(fail("should have found document"))

      val completeEndpoint = (complete \ "endpoint").as[String]
      val completeCreated = (complete \ "created" \ "$date").as[Long]
      val completeUpdated = (complete \ "updated" \ "$date").as[Long]

      completeEndpoint shouldBe someEndpoint
      completeCreated should be < completeUpdated

      val incomplete = await(repository.collection.
        find(Json.obj("authId" -> otherAuthId)).
        cursor[JsObject](ReadPreference.primary).headOption).getOrElse(fail("should have found document"))

      val incompleteEndpoint = incomplete \ "endpoint"
      val incompleteCreated = (incomplete \ "created" \ "$date").as[Long]
      val incompleteUpdated = (incomplete \ "updated" \ "$date").as[Long]

      incompleteEndpoint shouldBe a[JsUndefined]
      incompleteCreated shouldBe incompleteUpdated
    }

    "update endpoints for all authIds that share a token" in new Setup {
      val someAuthId = "auth-b"
      val otherAuthId = "auth-a"
      val yetAnotherAuthId = "auth-c"

      val someEndpoint = "/endpoint/p"
      val otherEndpoint = "/endpoint/q"
      val yetAnotherEndpoint = "/endpoint/r"

      await(repository.save(registrationWithDeviceAndroid, someAuthId))
      await(repository.save(registrationWithDeviceAndroid, otherAuthId))
      await(repository.save(registrationWithDeviceiOS, otherAuthId))
      await(repository.save(registrationWithDeviceWindows, yetAnotherAuthId))

      val sharedDeviceUpdated: Boolean = await(repository.saveEndpoint(registrationWithDeviceAndroid.token, someEndpoint))
      val iosDeviceUpdated: Boolean = await(repository.saveEndpoint(registrationWithDeviceiOS.token, otherEndpoint))
      val winDeviceUpdated: Boolean = await(repository.saveEndpoint(registrationWithDeviceWindows.token, yetAnotherEndpoint))

      sharedDeviceUpdated shouldBe true
      iosDeviceUpdated shouldBe true
      winDeviceUpdated shouldBe true

      val someAuthIdRegistrations: Seq[PushRegistrationPersist] = await(repository.findByAuthId(someAuthId))
      val otherAuthIdRegistrations: Seq[PushRegistrationPersist] = await(repository.findByAuthId(otherAuthId))
      val yetAnotherAuthIdRegistrations: Seq[PushRegistrationPersist] = await(repository.findByAuthId(yetAnotherAuthId))

      val someAuthIdEndpoints: Seq[String] = someAuthIdRegistrations.map(_.endpoint.getOrElse(fail("should have endpoint")))
      val otherAuthIdEndpoints: Seq[String] = otherAuthIdRegistrations.map(_.endpoint.getOrElse(fail("should have endpoint")))
      val yetAnotherAuthIdEndpoints: Seq[String] = yetAnotherAuthIdRegistrations.map(_.endpoint.getOrElse(fail("should have endpoint")))

      someAuthIdEndpoints.size shouldBe 1
      otherAuthIdEndpoints.size shouldBe 2
      yetAnotherAuthIdEndpoints.size shouldBe 1

      someAuthIdEndpoints.head shouldBe someEndpoint
      otherAuthIdEndpoints should contain allElementsOf Seq(someEndpoint, otherEndpoint)
      yetAnotherAuthIdEndpoints.head shouldBe yetAnotherEndpoint
    }

    "throw an exception when attempting to create(!) an endpoint with endpoint" in new Setup {
      val result = intercept[ReactiveMongoException] {
        repository.save(PushRegistration("token", Some(deviceAndroid), Some("/endpoint")), "id")
      }

      result.message should include("use saveEndpoint() instead")
    }

    "find a batch of tokens that do not have associated endpoints, oldest first" in new Setup {
      await(repository.save(registrationWithDeviceAndroid, "auth-a"))
      await(repository.save(registrationWithDeviceiOS, "auth-b"))
      await(repository.save(registrationWithDeviceWindows, "auth-c"))

      await(repository.saveEndpoint(registrationWithDeviceAndroid.token, "/some/endpoint/arn"))

      val result: Seq[PushRegistrationPersist] = await(repository.findIncompleteRegistrations(allPlatforms, maxRows))

      result.size shouldBe 2

      result.head.authId shouldBe "auth-b"
      result(1).authId shouldBe "auth-c"
    }

    "not return tokens for which the device is not known" in new Setup {
      await(repository.save(registrationUnknownDevice, "auth-a"))
      await(repository.save(registrationWithDeviceiOS, "auth-b"))

      val result: Seq[PushRegistrationPersist] = await(repository.findIncompleteRegistrations(allPlatforms, maxRows))

      result.size shouldBe 1
      result.head.token shouldBe registrationWithDeviceiOS.token
    }

    "not return tokens that have associated endpoints" in new Setup {
      await(repository.save(registrationWithDeviceAndroid, "auth-a"))
      await(repository.save(registrationWithDeviceiOS, "auth-b"))
      await(repository.save(registrationWithDeviceWindows, "auth-c"))

      await(repository.saveEndpoint(registrationWithDeviceAndroid.token, "/some/endpoint/a"))
      await(repository.saveEndpoint(registrationWithDeviceWindows.token, "/some/endpoint/c"))

      val result: Seq[PushRegistrationPersist] = await(repository.findIncompleteRegistrations(allPlatforms, maxRows))

      result.size shouldBe 1
      result.head.token shouldBe registrationWithDeviceiOS.token
    }

    "not return tokens that were previously returned but don't yet have associated endpoints" in new Setup {
      await(repository.save(registrationWithDeviceAndroid, "auth-a"))

      val first: Seq[PushRegistrationPersist] = await(repository.findIncompleteRegistrations(allPlatforms, maxRows))

      first.size shouldBe 1

      val second: Seq[PushRegistrationPersist] = await(repository.findIncompleteRegistrations(allPlatforms, maxRows))

      second.size shouldBe 0
    }

    "return only max-limit tokens when there are more than max-limit tokens that do not have associated endpoints" in new Setup {
      val someLimit = 10

      await {
        Future.sequence((1 to someLimit + 1).map(i => repository.save(registrationWithDeviceiOS, s"auth-$i")))
      }

      val allSaved: List[PushRegistrationPersist] = await(repository.findAll())

      allSaved.size should be > someLimit

      val result = await(repository.findIncompleteRegistrations(allPlatforms, someLimit))

      result.size shouldBe someLimit
    }

    "return only tokens for supported platforms" in new Setup {
      await(repository.save(registrationWithDeviceAndroid, "auth-a"))
      await(repository.save(registrationWithDeviceiOS, "auth-b"))
      await(repository.save(registrationWithDeviceWindows, "auth-c"))

      val result: Seq[PushRegistrationPersist] = await(repository.findIncompleteRegistrations(somePlatforms, maxRows))

      result.size shouldBe 2

      result.count(_.device.get.os == iOS) shouldBe 1
      result.count(_.device.get.os == Android) shouldBe 1
      result.count(_.device.get.os == Windows) shouldBe 0
    }

    "throw an exception if no supported platforms are supplied" in new Setup {
      val result = intercept[IllegalArgumentException] {
        repository.findIncompleteRegistrations(Seq.empty, maxRows)
      }

      result.getMessage shouldBe "Must have at least one platform!"
    }

    "return registrations that were not processed within a timeout period, oldest first" in new Setup {
      await(repository.save(registrationWithDeviceAndroid, "auth-a"))
      await(repository.save(registrationWithDeviceiOS, "auth-b"))
      await(repository.save(registrationWithDeviceWindows, "auth-c"))

      var allSaved = await(repository.findIncompleteRegistrations(allPlatforms, maxRows))

      allSaved.size shouldBe 3

      val saved = await(repository.saveEndpoint(registrationWithDeviceiOS.token, "/some/end/point"))

      saved shouldBe true

      Thread sleep 100

      val timeoutMillis = 25

      val incomplete = await(repository.findTimedOutRegistrations(timeoutMillis, maxRows))

      incomplete.size shouldBe 2

      incomplete.head.authId shouldBe "auth-a"
      incomplete(1).authId shouldBe "auth-c"
    }

    "remove registrations that do not match configured os details" in new Setup {
      await(repository.save(registrationWithDeviceAndroid, "auth-a"))
      await(repository.save(registrationUnknownDevice, "auth-b"))
      await(repository.save(registrationUnknownDevice, "auth-c"))
      await(repository.save(registrationWithDeviceiOS, "auth-d"))
      await(repository.save(registrationWithDeviceWindows, "auth-e"))

      val removed = await(repository.removeStaleRegistrations(somePlatforms, 60000L))

      removed shouldBe 3
    }

    "remove registrations that are incomplete after a timeout period has expired" in new Setup {
      await(repository.save(registrationWithDeviceiOS, "auth-a"))
      await(repository.save(registrationWithDeviceAndroid, "auth-b"))
      await(repository.saveEndpoint(registrationWithDeviceiOS.token, "/some/end/point"))

      Thread.sleep(1000L)

      await(repository.save(registrationWithDeviceiOS, "auth-c"))

      val removed = await(repository.removeStaleRegistrations(somePlatforms, 900L))

      removed shouldBe 1
    }

    "throw an exception if no platforms are retained" in new Setup {
      val result = intercept[IllegalArgumentException](repository.removeStaleRegistrations(Seq.empty, 10L))

      result.getMessage shouldBe "Must keep at least one platform!"
    }

    "remove tokens" in new Setup {
      await(repository.save(registrationWithDeviceAndroid, "auth-a"))
      await(repository.save(registrationWithDeviceiOS, "auth-b"))
      await(repository.save(registrationWithDeviceWindows, "auth-c"))

      val result: Boolean = await(repository.removeToken(registrationWithDeviceAndroid.token))

      result shouldBe true

      val remaining: Seq[PushRegistrationPersist] = await(repository.findIncompleteRegistrations(allPlatforms, maxRows))

      remaining.size shouldBe 2
    }

    "return a count of tokens that do not have an endpoint" in new Setup {
      for (i <- 1 to 12) {
        val device: Option[Device] = if (i % 3 == 0) Some(deviceiOS) else if (i % 5 == 0) Some(deviceAndroid) else if (i % 7 == 0) Some(deviceWindows) else None
        val token: String = s"token-$i"
        val auth: String = s"auth-$i"
        val registration = PushRegistration(token, device, None)
        await(repository.save(registration, auth))
      }

      val counts: Map[String, Int] = await(repository.countIncompleteRegistrations)

      counts.keySet.size shouldBe 4
      counts.get(NativeOS.ios) shouldBe Some(4)
      counts.get(NativeOS.android) shouldBe Some(2)
      counts.get(NativeOS.windows) shouldBe Some(1)
      counts.get("unknown") shouldBe Some(5)
    }
  }
}
