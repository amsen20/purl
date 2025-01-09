package purl

import pollerBear.runtime._
import purl.global.CurlGlobal
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
    @volatile var done = false
    CurlGlobal:
      withPassivePoller(16) { poller =>
        given PassivePoller = poller
        CurlMultiRuntime:
          CurlRequest(
            SimpleRequest(
              HttpVersion.V1_1,
              HttpMethod.GET,
              List(),
              "http://localhost:8080/http",
              ""
            )
          ) {
            case Success(response) =>
              assert(response.body.length > 0)
              done = true
            case Failure(exception) =>
              fail("couldn't get the response", exception)
              done = true
          }

          while !done do poller.waitUntil()
      }
  }

  test("status code") {
    @volatile var done = false
    CurlGlobal:
      withPassivePoller(16) { poller =>
        given PassivePoller = poller
        CurlMultiRuntime:
          for statusCode <- List(404, 500) do
            done = false
            CurlRequest(
              SimpleRequest(
                HttpVersion.V1_1,
                HttpMethod.GET,
                List(),
                "http://localhost:8080/http/" + statusCode.toString,
                ""
              )
            ) {
              case Success(response) =>
                assertEquals(response.status, statusCode)
                done = true
              case Failure(exception) =>
                fail("couldn't get the response", exception)
                done = true
            }

            while !done do poller.waitUntil()
      }
  }

  test("error") {
    @volatile var done = false
    CurlGlobal:
      withPassivePoller(16) { poller =>
        given PassivePoller = poller
        CurlMultiRuntime:
          CurlRequest(
            SimpleRequest(
              HttpVersion.V1_1,
              HttpMethod.GET,
              List(),
              "unsupported://server",
              ""
            )
          ) {
            case Success(response) =>
              fail("should have failed")
              done = true
            case Failure(exception) =>
              assert(exception.isInstanceOf[CurlError])
              done = true
          }

          while !done do poller.waitUntil()
      }
  }

  test("post echo") {
    @volatile var done = false
    CurlGlobal:
      withPassivePoller(16) { poller =>
        given PassivePoller = poller
        CurlMultiRuntime:
          for msg <- List("SOME_NOT_RANDOM_STRING_JRWIGJER)$%)##@R") do
            CurlRequest(
              SimpleRequest(
                HttpVersion.V1_1,
                HttpMethod.POST,
                List(),
                "http://localhost:8080/http/echo",
                msg
              )
            ) {
              case Success(response) =>
                assert(response.body.toString().contains(msg))
                done = true
              case Failure(exception) =>
                fail("couldn't get the response", exception)
                done = true
            }

            while !done do poller.waitUntil()
      }
  }
}
