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

import uk.gov.hmrc.mongo.{Updated, Saved}
import uk.gov.hmrc.pushregistration.config.MicroserviceAuditConnector
import uk.gov.hmrc.pushregistration.connectors.Authority
import uk.gov.hmrc.pushregistration.domain._
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.http.{UnauthorizedException, HeaderCarrier}
import uk.gov.hmrc.api.service._
import uk.gov.hmrc.api.sandbox._
import uk.gov.hmrc.pushregistration.repository.PushRegistrationRepository

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait PushRegistrationService {

  def register(registration: PushRegistration)(implicit hc: HeaderCarrier, authority:Option[Authority]): Future[Boolean]
  def find(id:String)(implicit hc: HeaderCarrier): Future[Seq[PushRegistration]]
}

trait LivePushRegistrationService extends PushRegistrationService with Auditor {

  def repository: PushRegistrationRepository

  override def register(registration:PushRegistration)(implicit hc: HeaderCarrier, authority:Option[Authority]): Future[Boolean] = {

    withAudit("register", PushRegistration.audit(registration)) {
      if (authority.isEmpty) Future.failed(new UnauthorizedException("Authority record not found for request!"))

      repository.save(registration, authority.get.authId).map { result =>
        result.updateType match {
          case Saved(_) => true
          case Updated(_,_) => false
        }
      }
    }
  }

  override def find(id:String)(implicit hc: HeaderCarrier): Future[Seq[PushRegistration]] = {
    withAudit("find", Map("authId" -> id)) {
      repository.findByAuthId(id).map { item => item.map(row => PushRegistration(row.token, row.device))
      }
    }
  }
}

object SandboxPushRegistrationService extends PushRegistrationService with FileResource {

  override def register(registration:PushRegistration)(implicit hc: HeaderCarrier, authority:Option[Authority]): Future[Boolean] = {
    Future.successful(true)
  }

  override def find(id:String)(implicit hc: HeaderCarrier): Future[Seq[PushRegistration]] =
    Future.successful(Seq(PushRegistration("token1", Some(Device(NativeOS.Android, "7.0", "Nexus 5"))), PushRegistration("token2", Some(Device(NativeOS.iOS, "1.4.5", "Apple 6")))))
}

object LivePushRegistrationService extends LivePushRegistrationService {
  override val auditConnector: AuditConnector = MicroserviceAuditConnector

  override val repository:PushRegistrationRepository = PushRegistrationRepository()
}
