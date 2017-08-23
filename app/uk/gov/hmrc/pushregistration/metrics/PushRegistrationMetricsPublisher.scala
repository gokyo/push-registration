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

package uk.gov.hmrc.pushregistration.metrics

import com.codahale.metrics.{Gauge, MetricRegistry}
import uk.gov.hmrc.play.graphite.MicroserviceMetrics
import uk.gov.hmrc.pushregistration.repository.PushRegistrationRepository

import scala.concurrent.Await
import scala.concurrent.duration._

trait PushRegistrationMetricsPublisher extends MicroserviceMetrics {
  val repository: PushRegistrationRepository

  val service = "push-registration"
  val registrations = "registrations"
  val gauge = "incomplete"
  val meter = "new"

  lazy val registry: MetricRegistry = metrics.defaultRegistry

  def registerGauges() {
    for (key <- Array("ios", "android", "windows", "unknown")) {
      if (!registry.getGauges.keySet().contains(s"$service.$registrations.$gauge.$key")) {
        registry.register(s"$service.$registrations.$gauge.$key", new Gauge[Long] {
          override def getValue: Long =
            Await.result(repository.countIncompleteRegistrations, 10 seconds)
              .getOrElse(key, throw new Exception(s"$key incomplete count missing"))
        })
      }
    }
  }

  def incrementNewRegistration(os: String): Unit = registry.meter(s"$service.$registrations.$meter.$os").mark()
}

object PushRegistrationMetricsPublisher extends PushRegistrationMetricsPublisher {
  override val repository: PushRegistrationRepository = PushRegistrationRepository()
}