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
    var done = false
    CurlGlobal:
      withPassivePoller { poller =>
        given PassivePoller = poller
        CurlMultiRuntime:
          CurlRequest(
            SimpleRequest(
              HttpVersion.V1_1,
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
              done = true
          }

          while !done do poller.waitUntil()
      }
  }

  test("status code") {
    var done = false
    CurlGlobal:
      withPassivePoller { poller =>
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
                "".getBytes()
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
    var done = false
    CurlGlobal:
      withPassivePoller { poller =>
        given PassivePoller = poller
        CurlMultiRuntime:
          CurlRequest(
            SimpleRequest(
              HttpVersion.V1_1,
              HttpMethod.GET,
              List(),
              "unsupported://server",
              "".getBytes()
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
    var done = false
    CurlGlobal:
      withPassivePoller { poller =>
        given PassivePoller = poller
        CurlMultiRuntime:
          for msg <- List("SOME_NOT_RANDOM_STRING_JRWIGJER)$%)##@R") do
            CurlRequest(
              SimpleRequest(
                HttpVersion.V1_1,
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
                done = true
            }

            while !done do poller.waitUntil()
      }
  }
}
