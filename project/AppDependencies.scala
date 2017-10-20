import sbt._

object AppDependencies {
  import play.core.PlayVersion
  import play.sbt.PlayImport._

  private val microserviceBootstrapVersion = "6.9.0"
  private val domainVersion = "5.0.0"
  private val scalaJVersion = "2.3.0"
  private val playHmrcApiVersion = "2.1.0"
  private val playReactiveMongo = "6.1.0"
  private val circuitBreaker = "3.1.0"
  private val reactiveMongoTest = "3.0.0"
  private val wireMockVersion = "2.9.0"
  private val hmrcTestVersion = "3.0.0"
  private val cucumberVersion = "1.2.5"
  private val mockitoVersion = "2.11.0"
  private val mongoLockVersion = "5.0.0"
  private val reactiveMongoJson = "0.12.4"
  private val scalatestplusPlayVersion = "2.0.1"

  val compile = Seq(
    ws,
    "uk.gov.hmrc" %% "microservice-bootstrap" % microserviceBootstrapVersion,
    "uk.gov.hmrc" %% "domain" % domainVersion,
    "uk.gov.hmrc" %% "reactive-circuit-breaker" % circuitBreaker,
    "uk.gov.hmrc" %% "play-hmrc-api" % playHmrcApiVersion,
    "uk.gov.hmrc" %% "play-reactivemongo" % playReactiveMongo,
    "uk.gov.hmrc" %% "mongo-lock" % mongoLockVersion
  )

  trait TestDependencies {
    lazy val scope: String = "test"
    lazy val test : Seq[ModuleID] = ???
  }

  object Test {
    def apply() = new TestDependencies {
      override lazy val test = Seq(
        "uk.gov.hmrc" %% "hmrctest" % hmrcTestVersion % "test,it",
        "org.scalaj" %% "scalaj-http" % scalaJVersion % "test,it",
        "com.typesafe.play" %% "play-test" % PlayVersion.current % "test,it",
        "com.github.tomakehurst" % "wiremock" % wireMockVersion % "test,it",
        "info.cukes" %% "cucumber-scala" % cucumberVersion % "test,it",
        "info.cukes" % "cucumber-junit" % cucumberVersion % "test,it",
        "uk.gov.hmrc" %% "reactivemongo-test" % reactiveMongoTest % scope,
        "org.reactivemongo" %% "reactivemongo-play-json" % reactiveMongoJson % "test,it"
      )
    }.test
  }

  object IntegrationTest {
    def apply() = new TestDependencies {

      override lazy val scope: String = "it"

      override lazy val test = Seq(
        "com.typesafe.play" %% "play-test" % PlayVersion.current % scope,
        "uk.gov.hmrc" %% "hmrctest" % hmrcTestVersion % scope,
        "org.mockito" % "mockito-core" % mockitoVersion % scope,
        "org.scalatestplus.play" %% "scalatestplus-play" % scalatestplusPlayVersion % scope
      )
    }.test
  }

  def apply() = compile ++ Test() ++ IntegrationTest()
}
