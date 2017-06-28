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

package uk.gov.hmrc.pushregistration.repository

import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._
import play.modules.reactivemongo.MongoDbConnection
import reactivemongo.api.commands.UpdateWriteResult
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.api.{DB, ReadPreference}
import reactivemongo.bson._
import reactivemongo.core.errors.ReactiveMongoException
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.mongo.{AtomicUpdate, BSONBuilderHelpers, DatabaseUpdate, ReactiveRepository}
import uk.gov.hmrc.pushregistration.domain.{Device, NativeOS, OS, PushRegistration}
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}


case class PushRegistrationPersist(id: BSONObjectID, token: String, authId: String, device: Option[Device], endpoint: Option[String])

object DeviceStore {

  implicit val reads: Reads[Device] = (
    (JsPath \ "os").read[NativeOS](NativeOS.readsFromStore) and
      (JsPath \ "osVersion").read[String] and
      (JsPath \ "appVersion").read[String] and
      (JsPath \ "model").read[String]
    ) (Device.apply _)

  val formats = Format(reads, Device.writes)
}

object PushRegistrationPersist {

  val mongoFormats: Format[PushRegistrationPersist] = ReactiveMongoFormats.mongoEntity({
    implicit val oidFormat = ReactiveMongoFormats.objectIdFormats
    implicit val device = DeviceStore.formats
    Format(Json.reads[PushRegistrationPersist], Json.writes[PushRegistrationPersist])
  })
}

object PushRegistrationRepository extends MongoDbConnection {
  lazy val mongo = new PushRegistrationMongoRepository

  def apply(): PushRegistrationRepository = mongo
}

class PushRegistrationMongoRepository(implicit mongo: () => DB)
  extends ReactiveRepository[PushRegistrationPersist, BSONObjectID]("registration", mongo, PushRegistrationPersist.mongoFormats, ReactiveMongoFormats.objectIdFormats)
    with AtomicUpdate[PushRegistrationPersist]
    with PushRegistrationRepository
    with BSONBuilderHelpers {

  override def ensureIndexes(implicit ec: ExecutionContext): Future[scala.Seq[Boolean]] = {
    Future.sequence(
      Seq(
        collection.indexesManager.ensure(
          Index(Seq("token" -> IndexType.Ascending), name = Some("tokenIdUnique"), unique = false)),
        collection.indexesManager.ensure(
          Index(Seq("updated" -> IndexType.Ascending), name = Some("updatedNotUnique"), unique = false)),
        collection.indexesManager.ensure(
          Index(Seq("authId" -> IndexType.Ascending), name = Some("authIdNotUnique"), unique = false)),
        collection.indexesManager.ensure(
          Index(Seq("device.os" -> IndexType.Ascending), name = Some("osNotUnique"), unique = false, sparse = true)),
        collection.indexesManager.ensure(
          Index(Seq("device.appVersion" -> IndexType.Ascending), name = Some("appVersionNotUnique"), unique = false, sparse = true)),
        collection.indexesManager.ensure(
          Index(Seq("device.model" -> IndexType.Ascending), name = Some("modelNotUnique"), unique = false, sparse = true))
      )
    )
  }

  override def isInsertion(suppliedId: BSONObjectID, returned: PushRegistrationPersist): Boolean =
    suppliedId.equals(returned.id)

  protected def findByTokenAndAuthId(token: String, authId: String) = BSONDocument("token" -> BSONString(token), "authId" -> authId)

  private def modifierForInsert(registration: PushRegistration, authId: String): BSONDocument = {
    val tokenAndDate = BSONDocument(
      "$setOnInsert" -> BSONDocument("token" -> registration.token),
      "$setOnInsert" -> BSONDocument("authId" -> authId),
      "$setOnInsert" -> BSONDocument("created" -> BSONDateTime(DateTimeUtils.now.getMillis)),
      "$set" -> BSONDocument("updated" -> BSONDateTime(DateTimeUtils.now.getMillis))
    )

    val deviceFields = registration.device.fold(BSONDocument.empty) { device =>
      BSONDocument(
        "$set" -> BSONDocument("device.os" -> OS.getId(device.os)),
        "$set" -> BSONDocument("device.osVersion" -> device.osVersion),
        "$set" -> BSONDocument("device.appVersion" -> device.appVersion),
        "$set" -> BSONDocument("device.model" -> device.model)
      )
    }
    tokenAndDate ++ deviceFields
  }

  override def findByAuthId(authId: String): Future[Seq[PushRegistrationPersist]] = {
    collection.
      find(Json.obj("authId" -> Json.toJson(authId))).
      sort(Json.obj("updated" -> JsNumber(-1))).
      cursor[PushRegistrationPersist](ReadPreference.primaryPreferred).
      collect[Seq]()
  }

  override def findIncompleteRegistrations(maxRows: Int): Future[Seq[PushRegistrationPersist]] = {
    def incompleteRegistrations = {
      collection.find(BSONDocument("$and" -> BSONArray(
        BSONDocument("endpoint" -> BSONDocument("$exists" -> false)),
        BSONDocument("processing" -> BSONDocument("$exists" -> false)),
        BSONDocument("device.os" -> BSONDocument("$exists" -> true))
      ))).
        sort(Json.obj("created" -> JsNumber(-1))).cursor[PushRegistrationPersist](ReadPreference.primaryPreferred).
        collect[List](maxRows)
    }

    processBatch(incompleteRegistrations)
  }

  override def findTimedOutRegistrations(timeoutMilliseconds: Long, maxRows: Int): Future[Seq[PushRegistrationPersist]] = {
    def timedOutRegistrations = {
      collection.find(BSONDocument("$and" -> BSONArray(
        BSONDocument("endpoint" -> BSONDocument("$exists" -> false)),
        BSONDocument("processing" -> BSONDocument("$lt" -> BSONDateTime(DateTimeUtils.now.getMillis - timeoutMilliseconds)))
      ))).
        sort(Json.obj("created" -> JsNumber(-1))).cursor[PushRegistrationPersist](ReadPreference.primaryPreferred).
        collect[List](maxRows)
    }

    processBatch(timedOutRegistrations)
  }

  // Note: In cases where multiple records exist with the same token, but different authId's,
  //       all records will be updated regardless of their processing state.
  override def saveEndpoint(token: String, endpoint: String): Future[Boolean] = {

    collection.update(
      BSONDocument("token" -> token),
      BSONDocument(
        "$set" -> BSONDocument("endpoint" -> endpoint, "updated" -> BSONDateTime(DateTimeUtils.now.getMillis)),
        "$unset" -> BSONDocument("processing" -> "")),
      upsert = false,
      multi = true
    ).map(
      _.nModified > 0
    )
  }

  override def removeToken(token: String): Future[Boolean] = {
    collection.remove(
      BSONDocument("token" -> token), firstMatchOnly = true
    ).map(!_.inError)
  }

  override def save(registration: PushRegistration, authId: String): Future[DatabaseUpdate[PushRegistrationPersist]] = {
    if (registration.endpoint.isDefined) {
      throw ReactiveMongoException("You must not create a push registration with endpoint, use saveEndpoint() instead!")
    }

    atomicUpsert(findByTokenAndAuthId(registration.token, authId), modifierForInsert(registration, authId))
  }

  private def processBatch(batch: Future[List[PushRegistrationPersist]]): Future[Seq[PushRegistrationPersist]] = {
    def setProcessing(batch: List[PushRegistrationPersist]) = {
      collection.update(
        BSONDocument("_id" -> BSONDocument("$in" -> batch.foldLeft(BSONArray())((a, p) => a.add(p.id)))),
        BSONDocument("$set" -> BSONDocument("processing" -> BSONDateTime(DateTimeUtils.now.getMillis))),
        upsert = false,
        multi = true
      )
    }

    def getBatchOrFailed(batch: List[PushRegistrationPersist], updateWriteResult: UpdateWriteResult) = {
      if (updateWriteResult.ok) Future.successful(batch) else Future.failed(new ReactiveMongoException {
        override def message: String = "failed to fetch incomplete registrations"
      })
    }

    for (
      registrations <- batch;
      updateResult <- setProcessing(registrations);
      incompleteRegistrations <- getBatchOrFailed(registrations, updateResult)
    ) yield incompleteRegistrations
  }

}

trait PushRegistrationRepository {
  def save(expectation: PushRegistration, authId: String): Future[DatabaseUpdate[PushRegistrationPersist]]

  def findByAuthId(authId: String): Future[Seq[PushRegistrationPersist]]

  def findIncompleteRegistrations(maxRows: Int): Future[Seq[PushRegistrationPersist]]

  def findTimedOutRegistrations(timeoutMilliseconds: Long, maxRows: Int): Future[Seq[PushRegistrationPersist]]

  def saveEndpoint(token: String, endpoint: String): Future[Boolean]

  def removeToken(token: String): Future[Boolean]
}

trait PushRegistrationMongoRepositoryTests extends PushRegistrationRepository {
  def removeAllRecords(): Future[Unit]
  def findByAuthIdAndToken(authId: String, token:String): Future[Option[PushRegistrationPersist]]
}

object PushRegistrationRepositoryTest extends MongoDbConnection {
  lazy val mongo = new PushRegistrationMongoRepositoryTest

  def apply(): PushRegistrationMongoRepositoryTests = mongo
}

class PushRegistrationMongoRepositoryTest(implicit mongo: () => DB) extends PushRegistrationMongoRepository with PushRegistrationMongoRepositoryTests {

  override def removeAllRecords(): Future[Unit] = {
    removeAll().map(_ => ())
  }
  override def findByAuthIdAndToken(authId: String, token:String): Future[Option[PushRegistrationPersist]] = {
    collection.
      find(BSONDocument("authId" -> authId) ++ BSONDocument("token" -> token))
      .one[PushRegistrationPersist](ReadPreference.primaryPreferred)
  }

}
