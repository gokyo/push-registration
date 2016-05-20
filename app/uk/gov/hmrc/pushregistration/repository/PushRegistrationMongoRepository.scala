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

package uk.gov.hmrc.pushregistration.repository

import play.api.libs.json._
import play.modules.reactivemongo.MongoDbConnection
import reactivemongo.api.DB
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson._
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.mongo.{AtomicUpdate, BSONBuilderHelpers, DatabaseUpdate, ReactiveRepository}
import uk.gov.hmrc.pushregistration.domain.PushRegistration
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

case class PushRegistrationPersist(id: BSONObjectID, deviceId:String, token:String, authId:String)

object PushRegistrationPersist {

  val mongoFormats: Format[PushRegistrationPersist] = ReactiveMongoFormats.mongoEntity(
  {
    implicit val oidFormat = ReactiveMongoFormats.objectIdFormats
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
          Index(Seq("deviceId" -> IndexType.Ascending), name = Some("deviceIdUnique"), unique = true)),
        collection.indexesManager.ensure(
          Index(Seq("created" -> IndexType.Ascending), name = Some("createdNotUnique"), unique = false)),
        collection.indexesManager.ensure(
          Index(Seq("authId" -> IndexType.Ascending), name = Some("authIdNotUnique"), unique = false))
      )
    )
  }

  override def isInsertion(suppliedId: BSONObjectID, returned: PushRegistrationPersist): Boolean =
    suppliedId.equals(returned.id)

  protected def findByDeviceIdAndAuthId(deviceId: String, authId:String) = BSONDocument("deviceId" -> BSONString(deviceId), "authId" -> authId)

  // The deviceId is unique and can only be associated to one authId and this is defined on creation of the record. The token can be updated for a single record.
  // The 'updated' attribute is used to resolve the latest deviceId and Token that is associated to an authId. The deviceId can change over time!
  private def modifierForInsert(registration: PushRegistration, authId: String): BSONDocument = {
    BSONDocument(
      "$setOnInsert" -> BSONDocument("deviceId" -> registration.deviceId),
      "$setOnInsert" -> BSONDocument("authId" -> authId),
      "$set" -> BSONDocument("updated" -> BSONDateTime(DateTimeUtils.now.getMillis)),
      "$set" -> BSONDocument("token" -> registration.token)
    )
  }

  def findByAuthId(authId: String): Future[Option[PushRegistrationPersist]] = {
    collection.find(Json.obj("authId" -> Json.toJson(authId))).sort(new JsObject(Seq("updated" -> JsNumber(-1)))).one[PushRegistrationPersist]
  }

  override def save(expectation: PushRegistration, authId:String): Future[DatabaseUpdate[PushRegistrationPersist]] = {
    atomicUpsert(findByDeviceIdAndAuthId(expectation.deviceId, authId), modifierForInsert(expectation, authId))
  }

}

trait PushRegistrationRepository {
  def save(expectation: PushRegistration, authId:String): Future[DatabaseUpdate[PushRegistrationPersist]]
  def findByAuthId(authId: String): Future[Option[PushRegistrationPersist]]
}
