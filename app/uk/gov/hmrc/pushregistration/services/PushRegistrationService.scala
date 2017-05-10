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

package uk.gov.hmrc.pushregistration.services

import org.joda.time.Duration
import play.modules.reactivemongo.MongoDbConnection
import uk.gov.hmrc.api.sandbox._
import uk.gov.hmrc.api.service._
import uk.gov.hmrc.lock.{LockKeeper, LockMongoRepository, LockRepository}
import uk.gov.hmrc.mongo.{Saved, Updated}
import uk.gov.hmrc.play.http.{HeaderCarrier, UnauthorizedException}
import uk.gov.hmrc.pushregistration.config.MicroserviceAuditConnector
import uk.gov.hmrc.pushregistration.connectors.Authority
import uk.gov.hmrc.pushregistration.domain._
import uk.gov.hmrc.pushregistration.repository.PushRegistrationRepository

import scala.collection.immutable.Iterable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future._

trait PushRegistrationService {

  def register(registration: PushRegistration)(implicit hc: HeaderCarrier, authority: Option[Authority]): Future[Boolean]

  def find(id: String)(implicit hc: HeaderCarrier): Future[Seq[PushRegistration]]

  def findIncompleteRegistrations(): Future[Option[Seq[PushRegistration]]]

  def registerEndpoints(endpoints: Map[String, Option[String]]): Future[Boolean]
}

trait LivePushRegistrationService extends PushRegistrationService with Auditor {

  def pushRegistrationRepository: PushRegistrationRepository

  def lockRepository: LockRepository

  val findIncompleteLockKeeper = new LockKeeper {
    override def repo: LockRepository = lockRepository

    override def lockId: String = "findIncompleteRegistrations"

    override val forceLockReleaseAfter: Duration = Duration.standardMinutes(2)
  }

  override def register(registration: PushRegistration)(implicit hc: HeaderCarrier, authority: Option[Authority]): Future[Boolean] = {

    withAudit("register", PushRegistration.audit(registration)) {
      if (authority.isEmpty) Future.failed(new UnauthorizedException("Authority record not found for request!"))

      pushRegistrationRepository.save(registration, authority.get.authInternalId).map { result =>
        result.updateType match {
          case Saved(_) => true
          case Updated(_, _) => false
        }
      }
    }
  }

  override def find(id: String)(implicit hc: HeaderCarrier): Future[Seq[PushRegistration]] = {
    withAudit("find", Map("authId" -> id)) {
      pushRegistrationRepository.findByAuthId(id).map { item => item.map(row => PushRegistration(row.token, row.device, row.endpoint))
      }
    }
  }

  override def findIncompleteRegistrations(): Future[Option[Seq[PushRegistration]]] = {
    findIncompleteLockKeeper.tryLock {
      pushRegistrationRepository.findIncompleteRegistrations()
        .map { item => item.map(row => PushRegistration(row.token, row.device, None)) }
    }
  }

  override def registerEndpoints(endpoints: Map[String, Option[String]]): Future[Boolean] = {
    val (registered, failed): (Map[String, Option[String]], Map[String, Option[String]]) = endpoints.partition(_._2.isDefined)

    val saved: Iterable[Future[Boolean]] = registered.map(r => pushRegistrationRepository.saveEndpoint(r._1, r._2.get))
    val removed: Set[Future[Boolean]] = failed.keySet.map(n => pushRegistrationRepository.removeToken(n))

    val allSaved: Future[Boolean] = for (results <- sequence(saved)) yield {
      results.foldLeft(true)((a, b) => a & b)
    }

    val allRemoved: Future[Boolean] = for (results <- sequence(removed)) yield {
      results.foldLeft(true)((a, b) => a & b)
    }

    for {as <- allSaved; ar <- allRemoved} yield as & ar
  }
}

object SandboxPushRegistrationService extends PushRegistrationService with FileResource {
  private val someTokens = Seq(PushRegistration("token1", Some(Device(NativeOS.Android, "7.0", "1.2", "Nexus 5")), None), PushRegistration("token2", Some(Device(NativeOS.iOS, "1.4.5", "1.3", "Apple 6")), None))

  override def register(registration: PushRegistration)(implicit hc: HeaderCarrier, authority: Option[Authority]): Future[Boolean] = {
    Future.successful(true)
  }

  override def find(id: String)(implicit hc: HeaderCarrier): Future[Seq[PushRegistration]] =
    Future.successful(someTokens)

  override def findIncompleteRegistrations(): Future[Option[Seq[PushRegistration]]] =
    Future.successful(Some(someTokens))

  override def registerEndpoints(endpoints: Map[String, Option[String]]): Future[Boolean] =
    Future.successful(false)
}

object LivePushRegistrationService extends LivePushRegistrationService with MongoDbConnection {

  override val auditConnector = MicroserviceAuditConnector

  override val pushRegistrationRepository: PushRegistrationRepository = PushRegistrationRepository()

  override val lockRepository: LockRepository = LockMongoRepository(db)
}
