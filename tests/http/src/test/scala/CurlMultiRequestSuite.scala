package ca.uwaterloo.plg.curl

import ca.uwaterloo.plg.curl.http.simple.{HttpMethod, HttpVersion, SimpleRequest, SimpleResponse}
import ca.uwaterloo.plg.curl.http.CurlRequest
import ca.uwaterloo.plg.curl.unsafe.{CurlMultiRuntime, CurlRuntimeContext}

import scala.util.Success
import gears.async.default.given
import scala.concurrent.ExecutionContext
import gears.async.*
import scala.util.Failure
import scala.util.Try
import scala.annotation.static

class CurlMultiRequestSuite extends munit.FunSuite {

  given ExecutionContext = ExecutionContext.global

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

  def request()(using Async)(using CurlRuntimeContext) =
    postRequest(getRequest())

  def getTime[T](body: () => T) =
    val time = System.currentTimeMillis()
    body()
    System.currentTimeMillis() - time

  test("async and sync comparison") {
    CurlMultiRuntime:
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
