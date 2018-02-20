package uk.gov.hmrc.pushregistration.controllers.action

import org.scalatest.concurrent.Eventually
import stubs.AuthStub.{authRecordExists, authRecordExistsWithoutInternalId, authRecordExistsWithoutNino}
import uk.gov.hmrc.auth.core.ConfidenceLevel
import uk.gov.hmrc.auth.core.ConfidenceLevel.{L200, L50}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.{ForbiddenException, HeaderCarrier, HttpResponse}
import utils.BaseISpec

import scala.concurrent.ExecutionContext

class AccountAccessControlISpec extends BaseISpec with Eventually  {
  implicit val hc = HeaderCarrier()
  implicit val ec: ExecutionContext = ExecutionContext.global

  val nino = Nino("CS100700A")
  val internalId = "internalId"

  def authConnector(response : HttpResponse, cl: ConfidenceLevel = L200) = new AccountAccessControl {
    override def serviceConfidenceLevel = cl
  }

  "grantAccess" should {
    "return authority record when confidence level is sufficient" in {
      authRecordExists(nino, L200, internalId)
      Authority(nino, L200, internalId) shouldBe await(AccountAccessControl.grantAccess())
    }

    "error with unauthorised when account has low CL" in {
      authRecordExists(nino, L50, internalId)

      intercept[ForbiddenException] {
        await(AccountAccessControl.grantAccess())
      }
    }

    "error when no NINO is found" in {
      authRecordExistsWithoutNino(L200, internalId)

      intercept[NinoNotFoundOnAccount] {
        await(AccountAccessControl.grantAccess())
      }
    }

    "error when no internal id is found" in {
      authRecordExistsWithoutInternalId(nino, L200)

      intercept[NoInternalId] {
        await(AccountAccessControl.grantAccess())
      }
    }
  }
}

