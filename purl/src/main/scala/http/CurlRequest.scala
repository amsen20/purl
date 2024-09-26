package purl
package http

import pollerBear.logger.PBLogger
import purl.http.simple._
import purl.internal._
import purl.unsafe.libcurl_const
import purl.unsafe.CURLcode
import purl.unsafe.CurlRuntimeContext
import scala.collection.mutable.ArrayBuffer
import scala.scalanative.unsafe._
import scala.util.Failure
import scala.util.Success
import scala.util.Try

object CurlRequest {

  type Cancellation = Throwable => Unit

  private def setup(
      handle: CurlEasy,
      sendData: RequestSend,
      recvData: RequestRecv,
      progressData: RequestProgress,
      version: HttpVersion,
      method: String,
      headers: CurlSList,
      uri: String
  )(
      using cc: CurlRuntimeContext,
      zone: Zone
  ): Unit = {
    PBLogger.log("Setting up the request...")
    // handle.setVerbose(true)

    handle.setCustomRequest(toCString(method))

    handle.setUpload(true)

    handle.setNoSignal(true)

    handle.setUrl(toCString(uri))

    val httpVersion = version match {
      case HttpVersion.V1_0 => libcurl_const.CURL_HTTP_VERSION_1_0
      case HttpVersion.V1_1 => libcurl_const.CURL_HTTP_VERSION_1_1
      case HttpVersion.V2   => libcurl_const.CURL_HTTP_VERSION_2
      case HttpVersion.V3   => libcurl_const.CURL_HTTP_VERSION_3
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

    PBLogger.log("Done setting up the request")
    cc.addHandle(handle.curl, recvData.onTerminated)
  }

  def apply(req: SimpleRequest)(onResponse: Try[SimpleResponse] => Unit)(
      using cc: CurlRuntimeContext
  ): Cancellation =
    PBLogger.log("Creating up a request...")
    val cleanUps = ArrayBuffer.empty[() => Unit]

    def afterHandleRemoved(res: Try[SimpleResponse]) =
      PBLogger.log("After handle removed")
      cleanUps.foreach(_())
      PBLogger.log("cleanUps all called")
      cleanUps.clear()
      PBLogger.log("cleanUps all cleared")
      onResponse(res)
      PBLogger.log("onResponse called")

    val (handle, handleCleanedUp) =
      try
        CurlEasy.getEasy()
      catch {
        case e: Throwable =>
          afterHandleRemoved(Failure(e))
          throw e
      }
    cleanUps.addOne(handleCleanedUp)

    def onResponseWithCleanUp(res: Try[SimpleResponse]) = {
      PBLogger.log(s"I am being called and the res is: ${res.getClass()}")
      try
        cc.removeHandle(
          handle.curl,
          _ => afterHandleRemoved(res)
        )
      catch {
        case e: Throwable =>
          afterHandleRemoved(res)
      }
    }

    try

      val (headers, slistCleanedUp) = CurlSList.getSList()
      cleanUps.addOne(slistCleanedUp)

      req.headers.foreach(headers.append(_))

      val zone = Zone.open()
      cleanUps.addOne(() => zone.close())

      val sendData = RequestSend(
        toCString(req.body)(
          using zone
        )
      )
      cc.keepTrack(sendData)
      cleanUps.addOne(() => cc.forget(sendData))

      val progressData = RequestProgress()
      cc.keepTrack(progressData)
      cleanUps.addOne(() => cc.forget(progressData))

      val recvData: RequestRecv = RequestRecv(onResponseWithCleanUp)
      cc.keepTrack(recvData)
      cleanUps.addOne(() => cc.forget(recvData))

      given Zone = zone
      setup(
        handle,
        sendData,
        recvData,
        progressData,
        req.httpVersion,
        req.method.toString,
        headers,
        req.uri
      )

    catch {
      case e: Throwable =>
        e.printStackTrace()
        onResponseWithCleanUp(Failure(e))
        throw e
    }

    PBLogger.log("done creating up the request")

    e => onResponseWithCleanUp(Failure(e))

}
