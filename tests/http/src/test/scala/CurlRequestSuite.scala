package ca.uwaterloo.plg.curl

import ca.uwaterloo.plg.curl.http.simple.{HttpMethod, HttpVersion, SimpleRequest}
import ca.uwaterloo.plg.curl.http.CurlRequest
import ca.uwaterloo.plg.curl.unsafe.CurlMultiRuntime
import ca.uwaterloo.plg.curl.CurlError

import scala.util.Success
import gears.async.default.given
import scala.concurrent.ExecutionContext
import gears.async.Async
import scala.util.Failure

class CurlRequestSuite extends munit.FunSuite {

  given ExecutionContext = ExecutionContext.global

  test("simple get request") {
    Async.blocking:
      CurlMultiRuntime(Int.MaxValue, Int.MaxValue):
        CurlRequest(
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
          case Failure(exception) =>
            fail("couldn't get the response", exception)
  }

  test("status code") {
    Async.blocking:
      CurlMultiRuntime(Int.MaxValue, Int.MaxValue):
        for statusCode <- List(404, 500) do
          CurlRequest(
            SimpleRequest(
              HttpVersion.V1_0,
              HttpMethod.GET,
              List(),
              "http://localhost:8080/http/" + statusCode.toString,
              "".getBytes(),
            )
          ) match
            case Success(response) =>
              assertEquals(response.status, statusCode)
            case Failure(exception) =>
              fail("couldn't get the response", exception)
  }

  test("error") {
    Async.blocking:
      CurlMultiRuntime(Int.MaxValue, Int.MaxValue):
        CurlRequest(
          SimpleRequest(
            HttpVersion.V1_0,
            HttpMethod.GET,
            List(),
            "unsupported://server",
            "".getBytes(),
          )
        ) match
          case Success(response) =>
            fail("should have failed")
          case Failure(exception) =>
            assert(exception.isInstanceOf[CurlError])
  }

  test("post echo") {
    Async.blocking:
      CurlMultiRuntime(Int.MaxValue, Int.MaxValue):
        for msg <- List("a") do
          CurlRequest(
            SimpleRequest(
              HttpVersion.V1_0,
              HttpMethod.POST,
              List(),
              "http://localhost:8080/http/echo",
              msg.getBytes(),
            )
          ) match
            case Success(response) =>
              assert(response.body.map(_.toChar).mkString.contains(msg))
            case Failure(exception) =>
              fail("couldn't get the response", exception)
  }
}
