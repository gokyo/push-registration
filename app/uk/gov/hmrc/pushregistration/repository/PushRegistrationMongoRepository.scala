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

import play.modules.reactivemongo.MongoDbConnection
import reactivemongo.api.{ReadPreference, DB}
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson._
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.mongo.{AtomicUpdate, BSONBuilderHelpers, DatabaseUpdate, ReactiveRepository}
import uk.gov.hmrc.pushregistration.domain.{NativeOS, OS, Device, PushRegistration}
import uk.gov.hmrc.time.DateTimeUtils
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}


case class PushRegistrationPersist(id: BSONObjectID, token:String, authId:String, device:Option[Device])

object DeviceStore {

  implicit val reads: Reads[Device] = (
    (JsPath \ "os").read[NativeOS](NativeOS.readsFromStore) and
    (JsPath \ "osVersion").read[String] and
    (JsPath \ "appVersion").read[String] and
    (JsPath \ "model").read[String]
  )(Device.apply _)

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

  protected def findByTokenAndAuthId(token: String, authId:String) = BSONDocument("token" -> BSONString(token), "authId" -> authId)

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

  def findByAuthId(authId: String): Future[Seq[PushRegistrationPersist]] = {
    collection.
      find(Json.obj("authId" -> Json.toJson(authId))).
      sort(Json.obj("updated" -> JsNumber(-1))).
      cursor[PushRegistrationPersist](ReadPreference.primaryPreferred).
      collect[Seq]()
  }

  override def save(registration: PushRegistration, authId:String): Future[DatabaseUpdate[PushRegistrationPersist]] = {
    atomicUpsert(findByTokenAndAuthId(registration.token, authId), modifierForInsert(registration, authId))
  }
}

trait PushRegistrationRepository {
  def save(expectation: PushRegistration, authId:String): Future[DatabaseUpdate[PushRegistrationPersist]]
  def findByAuthId(authId: String): Future[Seq[PushRegistrationPersist]]
}
