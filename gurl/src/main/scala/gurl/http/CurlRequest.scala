package gurl
package http

import gurl.internal._
import gurl.unsafe.CurlRuntimeContext
import gurl.http.simple._
import gurl.unsafe.libcurl_const
import gurl.unsafe.CURLcode

import scala.scalanative.unsafe._
import gears.async.Async
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import java.util.concurrent.CancellationException

object CurlRequest {
  private def setup(
      handle: CurlEasy,
      sendData: RequestSend,
      recvData: RequestRecv,
      progressData: RequestProgress,
      version: String,
      method: String,
      headers: CurlSList,
      uri: String,
  )(using cc: CurlRuntimeContext, zone: Zone): Unit = {
    // handle.setVerbose(true)

    handle.setCustomRequest(toCString(method))

    handle.setUpload(true)

    handle.setNoSignal(true)

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

    handle.setNoProgress(false)
    handle.setProgressData(Utils.toPtr(progressData))
    handle.setProgressFunction(RequestProgress.progressCallback(_, _, _, _, _))

    cc.addHandle(handle.curl, recvData.onTerminated)
  }

  def apply(req: SimpleRequest)(using cc: CurlRuntimeContext)(using Async): Try[SimpleResponse] =
    try {
      val sendData = RequestSend(req.body)
      val recvData = RequestRecv()
      val progressData = RequestProgress()

      try {
        cc.keepTrack(sendData)
        cc.keepTrack(recvData)
        cc.keepTrack(progressData)

        CurlEasy.withEasy { handle =>
          Zone:
            CurlSList.withSList(headers =>
              req.headers.foreach(headers.append(_))
              try {
                setup(
                  handle,
                  sendData,
                  recvData,
                  progressData,
                  req.httpVersion.toString,
                  req.method.toString,
                  headers,
                  req.uri,
                )
                recvData.response()
              } catch {
                case e: Throwable =>
                  cc.removeHandle(handle.curl)
                  throw e
              }
            )
        }
      } finally {
        cc.forget(sendData)
        cc.forget(recvData)
        cc.forget(progressData)
      }
    } catch {
      case e: CancellationException =>
        throw e
      case e: Throwable => Failure(e)
    }
}
