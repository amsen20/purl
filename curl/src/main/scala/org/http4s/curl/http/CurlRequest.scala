package org.http4s.curl.http

import org.http4s.curl.internal.Utils
import org.http4s.curl.internal._
import org.http4s.curl.internal.CurlEasy
import org.http4s.curl.unsafe.CurlRuntimeContext
import org.http4s.curl.http.simple._
import org.http4s.curl.unsafe.libcurl_const
import org.http4s.curl.internal.Utils.toPtr

import scala.scalanative.unsafe._
import gears.async.Async
import scala.util.Try
import scala.util.Failure
import scala.util.Success

object CurlRequest {
  private def setup(
      handle: CurlEasy,
      sendData: RequestSend,
      recvData: RequestRecv,
      version: String,
      method: String,
      headers: CurlSList,
      uri: String,
  )(using cc: CurlRuntimeContext, zone: Zone): Unit = {
    // handle.setVerbose(true)
    handle.setCustomRequest(toCString(method))

    handle.setUpload(true)

    handle.setUrl(toCString(uri))

    val httpVersion = version match {
      case "1.0" => libcurl_const.CURL_HTTP_VERSION_1_0
      case "1.1" => libcurl_const.CURL_HTTP_VERSION_1_1
      case "2" => libcurl_const.CURL_HTTP_VERSION_2
      case "3" => libcurl_const.CURL_HTTP_VERSION_3
      case _ => libcurl_const.CURL_HTTP_VERSION_NONE
    }
    handle.setHttpVersion(toSize(httpVersion))

    handle.setHttpHeader(headers.list)

    handle.setReadData(Utils.toPtr(sendData))
    handle.setReadFunction(RequestSend.readCallback(_, _, _, _))

    handle.setHeaderData(Utils.toPtr(recvData))
    handle.setHeaderFunction(RequestRecv.headerCallback(_, _, _, _))

    handle.setWriteData(Utils.toPtr(recvData))
    handle.setWriteFunction(RequestRecv.writeCallback(_, _, _, _))

    cc.addHandle(handle.curl, recvData.onTerminated)
  }

  def apply(req: SimpleRequest)(using CurlRuntimeContext)(using Async): Try[SimpleResponse] =
    try
      CurlEasy.withEasy { handle =>
        val sendData = RequestSend(req.body)
        val recvData = RequestRecv()
        Zone:
          CurlSList.withSList(headers =>
            req.headers.foreach(headers.append(_))
            setup(
              handle,
              sendData,
              recvData,
              req.httpVersion.toString,
              req.method.toString,
              headers,
              req.uri,
            )
            recvData.response()
          )
      }
    catch {
      case e: Throwable => Failure(e)
    }
}
