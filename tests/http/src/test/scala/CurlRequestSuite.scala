package purl

import gears.async.default.given
import gears.async.Async
import pollerBear.runtime._
import purl.http.simple.{ HttpMethod, HttpVersion, SimpleRequest }
import purl.http.CurlRequest
import purl.multi.CurlMultiRuntime
import purl.CurlError
import scala.concurrent.ExecutionContext
import scala.util.Failure
import scala.util.Success

class CurlRequestSuite extends munit.FunSuite {

  given ExecutionContext = ExecutionContext.global

  test("simple get request") {
    var done = false
    withPassivePoller { poller =>
      given PassivePoller = poller
      CurlMultiRuntime:
        CurlRequest(
          SimpleRequest(
            HttpVersion.V1_0,
            HttpMethod.GET,
            List(),
            "http://localhost:8080/http",
            "".getBytes()
          )
        ) {
          case Success(response) =>
            assert(response.body.nonEmpty)
            done = true
          case Failure(exception) =>
            fail("couldn't get the response", exception)
        }

        while !done do poller.waitUntil()
    }
  }

  test("status code") {
    var done = false
    withPassivePoller { poller =>
      given PassivePoller = poller
      CurlMultiRuntime:
        for statusCode <- List(404, 500) do
          CurlRequest(
            SimpleRequest(
              HttpVersion.V1_0,
              HttpMethod.GET,
              List(),
              "http://localhost:8080/http/" + statusCode.toString,
              "".getBytes()
            )
          ) {
            case Success(response) =>
              assertEquals(response.status, statusCode)
              done = true
            case Failure(exception) =>
              fail("couldn't get the response", exception)
          }

          while !done do poller.waitUntil()
    }
  }

  test("error") {
    var done = false
    withPassivePoller { poller =>
      given PassivePoller = poller
      CurlMultiRuntime:
        CurlRequest(
          SimpleRequest(
            HttpVersion.V1_0,
            HttpMethod.GET,
            List(),
            "unsupported://server",
            "".getBytes()
          )
        ) {
          case Success(response) =>
            fail("should have failed")
          case Failure(exception) =>
            assert(exception.isInstanceOf[CurlError])
            done = true
        }

        while !done do poller.waitUntil()
    }
  }

  test("post echo") {
    var done = false
    withPassivePoller { poller =>
      given PassivePoller = poller
      CurlMultiRuntime:
        for msg <- List("a") do
          CurlRequest(
            SimpleRequest(
              HttpVersion.V1_0,
              HttpMethod.POST,
              List(),
              "http://localhost:8080/http/echo",
              msg.getBytes()
            )
          ) {
            case Success(response) =>
              assert(response.body.map(_.toChar).mkString.contains(msg))
              done = true
            case Failure(exception) =>
              fail("couldn't get the response", exception)
          }

          while !done do poller.waitUntil()
    }
  }
}
