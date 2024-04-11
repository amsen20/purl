package example

import org.http4s.curl.http.CurlRequest
import org.http4s.curl.unsafe.CurlMultiRuntime
import org.http4s.curl.http.simple.SimpleRequest
import org.http4s.curl.http.simple.HttpVersion
import org.http4s.curl.http.simple.HttpMethod

import scala.util.Success
import gears.async.default.given
import scala.concurrent.ExecutionContext
import gears.async.*
import scala.util.Failure
import org.http4s.curl.unsafe.CurlRuntimeContext
import scala.util.Try
import org.http4s.curl.http.simple.SimpleResponse
import scala.annotation.static

object Example {

  given ExecutionContext = ExecutionContext.global

  def postRequest(msg: String)(using Async)(using CurlRuntimeContext) =
    println("sending the post request")
    CurlRequest(
      SimpleRequest(
        HttpVersion.V1_0,
        HttpMethod.POST,
        List(),
        "http://localhost:8080/http/echo",
        msg,
      )
    ) match
      case Success(response) => assert(response.body == msg)
      case Failure(exception) => throw exception
    println("end sending the post request")

  def getRequest()(using Async)(using CurlRuntimeContext) =
    println("sending the get request")
    val res = CurlRequest(
      SimpleRequest(
        HttpVersion.V1_0,
        HttpMethod.GET,
        List(),
        "http://localhost:8080/http",
        "",
      )
    ) match
      case Success(response) =>
        assert(response.body.nonEmpty)
        response.body
      case Failure(exception) =>
        throw exception
    println("end sending the get request")
    res

  def request()(using Async)(using CurlRuntimeContext) =
    postRequest(getRequest())

  def getTime[T](body: () => T) =
    val time = System.currentTimeMillis()
    body()
    (System.currentTimeMillis() - time)


  def test(): Unit = {
    CurlMultiRuntime:
      Async.blocking:
        val elems = (0 to 0)
        // Sync
        // println("Sync time: " + getTime(() => elems.map(_ => request())))
        // Async
        println("Async time: " + getTime(() => elems.map(_ => Future(request())).awaitAll))
  }
}
