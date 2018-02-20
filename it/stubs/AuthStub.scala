package stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import play.api.libs.json.Json.obj
import uk.gov.hmrc.auth.core.ConfidenceLevel
import uk.gov.hmrc.domain.Nino

object AuthStub {
  def authRecordExists(nino: Nino, confidenceLevel: ConfidenceLevel, internalId: String): Unit = {
    stubFor(post(urlEqualTo("/auth/authorise")).withRequestBody(equalToJson(
      """{ "authorise": [], "retrieve": ["nino","confidenceLevel",'internalId'] }""".stripMargin, true, false)).willReturn(
      aResponse().withStatus(200).withBody(obj("confidenceLevel" -> confidenceLevel.level, "nino" -> nino.nino, "internalId" -> internalId).toString)))
  }

  def authRecordExistsWithoutNino(confidenceLevel: ConfidenceLevel, internalId: String): Unit = {
    stubFor(post(urlEqualTo("/auth/authorise")).withRequestBody(equalToJson(
      """{ "authorise": [], "retrieve": ["nino",'internalId',"confidenceLevel"] }""".stripMargin, true, false)).willReturn(
      aResponse().withStatus(200).withBody(obj("confidenceLevel" -> confidenceLevel.level, "internalId" -> "internalId").toString)))
  }

  def authRecordExistsWithoutInternalId(nino: Nino, confidenceLevel: ConfidenceLevel): Unit = {
    stubFor(post(urlEqualTo("/auth/authorise")).withRequestBody(equalToJson(
      """{ "authorise": [], "retrieve": ["nino",'internalId',"confidenceLevel"] }""".stripMargin, true, false)).willReturn(
      aResponse().withStatus(200).withBody(obj("confidenceLevel" -> confidenceLevel.level, "nino" -> nino.nino).toString)))
  }
}

