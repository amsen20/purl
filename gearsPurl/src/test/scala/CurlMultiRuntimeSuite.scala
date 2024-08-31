package gearsPurl

import gears.async.*
import gears.async.default.given
import pollerBear.runtime._
import purl.global.CurlGlobal
import purl.http.simple.{ HttpMethod, HttpVersion, SimpleRequest, SimpleResponse }
import purl.http.CurlRequest
import purl.multi.CurlMultiRuntime
import purl.unsafe.CurlRuntimeContext
import purl.CurlError
import scala.annotation.internal.requiresCapability
import scala.annotation.static
import scala.compiletime.ops.long
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext
import scala.util.Failure
import scala.util.Success
import scala.util.Try

class CurlMultiRuntimeSuite extends munit.FunSuite {

  given ExecutionContext = ExecutionContext.global

  val longMsg = "a" * 1000

  def postRequest(msg: String)(
      using Async,
      CurlRuntimeContext
  ) =
    gearsPurl.request(
      SimpleRequest(
        HttpVersion.V1_1,
        HttpMethod.POST,
        List(),
        "http://localhost:8080/http/echo",
        msg.getBytes()
      )
    ) match
      case Success(response)  => assertEquals(response.body.map(_.toChar).mkString, msg)
      case Failure(exception) => fail("couldn't get the response", exception)

  def getRequest()(
      using Async,
      CurlRuntimeContext
  ) =
    val res = gearsPurl.request(
      SimpleRequest(
        HttpVersion.V1_1,
        HttpMethod.GET,
        List(),
        "http://localhost:8080/http",
        "".getBytes()
      )
    ) match
      case Success(response) =>
        assert(response.body.nonEmpty)
        response.body
      case Failure(exception) =>
        fail("couldn't get the response", exception)
    res.map(_.toChar).mkString

  def failed_request()(
      using Async,
      CurlRuntimeContext
  ) =
    gearsPurl.request(
      SimpleRequest(
        HttpVersion.V1_1,
        HttpMethod.GET,
        List(),
        "unsupported://server",
        "".getBytes()
      )
    ) match
      case Success(response)  => fail("shouldn't get a response")
      case Failure(exception) => assert(exception.isInstanceOf[CurlError])

  def request()(
      using Async,
      CurlRuntimeContext
  ) =
    postRequest(getRequest())

  def longRequest()(
      using Async,
      CurlRuntimeContext
  ) =
    postRequest(longMsg)

  def getTime[T](body: () => T) =
    val time = System.currentTimeMillis()
    body()
    System.currentTimeMillis() - time

  test("a simple long request") {
    CurlGlobal:
      withActivePoller:
        CurlMultiRuntime:
          Async.blocking:
            longRequest()
  }

  test("creation and deletion of the multiple runtime") {
    CurlGlobal:
      withActivePoller:
        Async.blocking:
          for _ <- 0 to 3 do
            CurlMultiRuntime:
              request()
  }

  test("cancelling a long request") {
    CurlGlobal:
      CurlGlobal:
        withActivePoller:
          CurlMultiRuntime:
            Async.blocking:
              val res = withTimeoutOption(FiniteDuration(500, "micro")):
                Async.group(
                  (for _ <- 0 to 10 yield Future(longRequest())).awaitAll
                )
              assert(res.isEmpty)
  }

  test("mixture of different types of requests") {
    CurlGlobal:
      withActivePoller:
        CurlMultiRuntime:
          Async.blocking:
            withTimeoutOption(FiniteDuration(1000, "ms")):
              while true do
                Async.group(
                  Seq(
                    Future(longRequest()),
                    Future(request()),
                    Future(failed_request())
                  ).awaitFirst
                )
  }

  /**
   * FIXME: there is a scenario that `getRequest` returns
   * but the returning value does not reach the caller function and makes
   * the test stuck for ever.
   */
  test("async and sync") {
    CurlGlobal:
      withActivePoller:
        CurlMultiRuntime:
          Async.blocking:
            val n     = 1000
            val elems = 0 to n - 1
            // Sync
            val syncTime = getTime(() =>
              elems.map(_ =>
                getRequest()
                longRequest()
              )
            )
            // Async
            val asyncTime = getTime(() =>
              elems
                .map(_ =>
                  Future:
                    getRequest()
                    longRequest()
                )
                .awaitAll
            )
  }
}
