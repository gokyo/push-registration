/*
 * Copyright 2018 HM Revenue & Customs
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
import play.api.Logger
import play.api.libs.json.JsString
import play.modules.reactivemongo.MongoDbConnection
import uk.gov.hmrc.api.sandbox._
import uk.gov.hmrc.api.service._
import uk.gov.hmrc.http.{HeaderCarrier, UnauthorizedException}
import uk.gov.hmrc.lock.{LockKeeper, LockMongoRepository, LockRepository}
import uk.gov.hmrc.mongo.{Saved, Updated}
import uk.gov.hmrc.play.config.ServicesConfig
import uk.gov.hmrc.pushregistration.config.MicroserviceAuditConnector
import uk.gov.hmrc.pushregistration.controllers.action.Authority
import uk.gov.hmrc.pushregistration.domain._
import uk.gov.hmrc.pushregistration.metrics.PushRegistrationMetricsPublisher
import uk.gov.hmrc.pushregistration.repository.PushRegistrationRepository

import scala.collection.immutable.Iterable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future._

trait PushRegistrationService {

  def register(registration: PushRegistration)(implicit hc: HeaderCarrier, authority: Option[Authority]): Future[Boolean]

  def find(id: String)(implicit hc: HeaderCarrier): Future[Seq[PushRegistration]]

  def findIncompleteRegistrations(): Future[Option[Seq[PushRegistration]]]

  def findTimedOutRegistrations(): Future[Option[Seq[PushRegistration]]]

  def registerEndpoints(endpoints: Map[String, Option[String]]): Future[Boolean]

  def removeStaleRegistrations: Future[Int]
}

trait LivePushRegistrationService extends PushRegistrationService with Auditor {

  val batchSize: Int

  val timeoutMillis: Long

  val staleTimeoutMillis: Long

  val configuredPlatforms: Seq[NativeOS]

  def pushRegistrationRepository: PushRegistrationRepository

  def lockRepository: LockRepository

  val findIncompleteLockKeeper = new LockKeeper {
    override def repo: LockRepository = lockRepository

    override def lockId: String = "findIncompleteRegistrations"

    override val forceLockReleaseAfter: Duration = Duration.standardMinutes(2)
  }

  val findTimedOutLockKeeper = new LockKeeper {
    override def repo: LockRepository = lockRepository

    override def lockId: String = "findTimedOutRegistrations"

    override val forceLockReleaseAfter: Duration = Duration.standardMinutes(2)
  }

  override def register(registration: PushRegistration)(implicit hc: HeaderCarrier, authority: Option[Authority]): Future[Boolean] = {

    withAudit("register", PushRegistration.audit(registration)) {
      if (authority.isEmpty) Future.failed(new UnauthorizedException("Authority record not found for request!"))

      pushRegistrationRepository.save(registration, authority.get.authInternalId).map { result =>
        result.updateType match {
          case Saved(_) =>
            val os = registration.device.map(d => NativeOS.getName(d.os)).getOrElse("unknown")
            PushRegistrationMetricsPublisher.incrementNewRegistration(os)
            true
          case Updated(_, _) =>
            false
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
      pushRegistrationRepository.findIncompleteRegistrations(configuredPlatforms, batchSize).map { item => item.map(row => PushRegistration(row.token, row.device, None)) }.
        andThen { case batch =>
          Logger.info(s"asked for $batchSize incomplete registrations; got ${batch.getOrElse(Seq.empty).size}")
        }
    }
  }

  override def findTimedOutRegistrations(): Future[Option[Seq[PushRegistration]]] = {
    findTimedOutLockKeeper.tryLock {
      pushRegistrationRepository.findTimedOutRegistrations(timeoutMillis, batchSize).map { item => item.map(row => PushRegistration(row.token, row.device, None)) }.
        andThen { case batch =>
          Logger.info(s"asked for $batchSize timed-out registrations; got ${batch.getOrElse(Seq.empty).size}")
        }
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

  override def removeStaleRegistrations: Future[Int] = {
    pushRegistrationRepository.removeStaleRegistrations(configuredPlatforms, staleTimeoutMillis).andThen{ case result =>
      val count = result.getOrElse(0)
      Logger.info(s"removed $count stale registrations which did not have device info or which were incomplete after ${staleTimeoutMillis / 1000} seconds")
    }
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

  override def findTimedOutRegistrations(): Future[Option[Seq[PushRegistration]]] =
  Future.successful(Some(someTokens))

  override def registerEndpoints(endpoints: Map[String, Option[String]]): Future[Boolean] =
    Future.successful(false)

  override def removeStaleRegistrations =
    Future.successful(0)
}

object LivePushRegistrationService extends LivePushRegistrationService with ServicesConfig with MongoDbConnection {
  override val batchSize: Int = getInt("unregisteredBatchSize")

  override val timeoutMillis: Long = getInt("timeoutSeconds") * 1000L

  override val staleTimeoutMillis: Long = getInt("staleSeconds") * 1000L

  override val configuredPlatforms: Seq[NativeOS] = getString("configuredPlatforms").split(",").map{s => NativeOS.reads.reads(JsString(s)).get}

  override val auditConnector = MicroserviceAuditConnector

  override val pushRegistrationRepository: PushRegistrationRepository = PushRegistrationRepository()

  override val lockRepository: LockRepository = LockMongoRepository(db)
}
