package gurl

import gurl.http.simple.{HttpMethod, HttpVersion, SimpleRequest, SimpleResponse}
import gurl.http.CurlRequest
import gurl.unsafe.CurlRuntimeContext
import gurl.multi.CurlMultiRuntime

import scala.util.Success
import gears.async.default.given
import scala.concurrent.ExecutionContext
import gears.async.*
import scala.util.Failure
import scala.util.Try
import scala.annotation.static
import scala.annotation.internal.requiresCapability
import scala.concurrent.duration.FiniteDuration
import gurl.CurlError

class CurlMultiRuntimeSuite extends munit.FunSuite {

  given ExecutionContext = ExecutionContext.global

  val longMsg = "a" * 1000

  def postRequest(msg: String)(using Async)(using CurlRuntimeContext) =
    CurlRequest(
      SimpleRequest(
        HttpVersion.V1_0,
        HttpMethod.POST,
        List(),
        "http://localhost:8080/http/echo",
        msg.getBytes(),
      )
    ) match
      case Success(response) => assertEquals(response.body.map(_.toChar).mkString, msg)
      case Failure(exception) => fail("couldn't get the response", exception)

  def getRequest()(using Async)(using CurlRuntimeContext) =
    val res = CurlRequest(
      SimpleRequest(
        HttpVersion.V1_0,
        HttpMethod.GET,
        List(),
        "http://localhost:8080/http",
        "".getBytes(),
      )
    ) match
      case Success(response) =>
        assert(response.body.nonEmpty)
        response.body
      case Failure(exception) =>
        fail("couldn't get the response", exception)
    res.map(_.toChar).mkString

  def failed_request()(using Async)(using CurlRuntimeContext) =
    CurlRequest(
      SimpleRequest(
        HttpVersion.V1_0,
        HttpMethod.GET,
        List(),
        "unsupported://server",
        "".getBytes(),
      )
    ) match
      case Success(response) => fail("shouldn't get a response")
      case Failure(exception) => assert(exception.isInstanceOf[CurlError])

  def request()(using Async)(using CurlRuntimeContext) =
    postRequest(getRequest())

  def longRequest()(using Async)(using CurlRuntimeContext) =
    postRequest(longMsg)

  def getTime[T](body: () => T) =
    val time = System.currentTimeMillis()
    body()
    System.currentTimeMillis() - time

  test("creation and deletion of the multiple runtime") {
    Async.blocking:
      for _ <- 0 to 3 do
        CurlMultiRuntime(Int.MaxValue, Int.MaxValue):
          request()
  }

  test("cancelling a long request") {
    CurlMultiRuntime(Int.MaxValue, Int.MaxValue):
      Async.blocking:
        val res = withTimeoutOption(FiniteDuration(100, "ms")):
          Async.group(
            Seq(
              Future(longRequest()),
              Future(longRequest()),
              Future(longRequest()),
            ).awaitAll
          )
        assert(res.isEmpty)
  }

  test("mixture of different types of requests") {
    CurlMultiRuntime(Int.MaxValue, Int.MaxValue):
      Async.blocking:
        withTimeoutOption(FiniteDuration(1000, "ms")):
          while true do
            Async.group(
              Seq(
                Future(longRequest()),
                Future(request()),
                Future(failed_request()),
              ).awaitFirst
            )
  }

  test("async and sync comparison") {
    CurlMultiRuntime(Int.MaxValue, Int.MaxValue):
      Async.blocking:
        val n = 3
        val elems = 0 to n - 1
        // Sync
        val syncTime = getTime(() => elems.map(_ => request()))
        // Async
        val asyncTime = getTime(() => elems.map(_ => Future(request())).awaitAll)
        assert(syncTime > asyncTime * (n - 1))
  }
}
