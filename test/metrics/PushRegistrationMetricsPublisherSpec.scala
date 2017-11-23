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

package metrics


import com.codahale.metrics.Gauge
import controllers.IncompleteCounts
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}

import scala.collection.JavaConverters._
import scala.collection.mutable

class PushRegistrationMetricsPublisherSpec extends UnitSpec with
  MongoSpecSupport with
  BeforeAndAfterEach with
  ScalaFutures with
  WithFakeApplication with
  Eventually {

  "PushRegistrationMetricsPublisher" should {

    for ((os, incompleteRegistrations) <- Array(("ios",4),("android",3), ("windows",2), ("unknown",1))) {
      s"return the number of incomplete $os registrations" in new IncompleteCounts {
        val all: mutable.Map[String, Gauge[_]] = publisher.registry.getGauges.asScala

        val gauge: Gauge[_] = all.getOrElse(s"push-registration.registrations.incomplete.$os", fail(s"expected a value for $os"))

        gauge.getValue shouldBe incompleteRegistrations
      }
    }

    s"report zero where there are no incomplete registrations" in new IncompleteCounts {
      override def counts = Map("ios" -> 4, "windows" -> 2, "unknown" -> 1)

      val os = "android"

      val all: mutable.Map[String, Gauge[_]] = publisher.registry.getGauges.asScala

      val gauge: Gauge[_] = all.getOrElse(s"push-registration.registrations.incomplete.$os", fail(s"expected a value for $os"))

      gauge.getValue shouldBe 0
    }
  }
}