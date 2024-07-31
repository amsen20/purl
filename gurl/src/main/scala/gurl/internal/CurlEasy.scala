package gurl
package internal

import gurl.CurlError
import gurl.unsafe.CurlRuntimeContext
import gurl.unsafe.libcurl._
import gurl.unsafe.libcurl_const._

import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

final private[gurl] class CurlEasy private (val curl: Ptr[CURL], errBuffer: Ptr[CChar]) {

  @inline private def throwOnError(thunk: => CURLcode): Unit = {
    val code = thunk
    if (code.isError) {
      val details = fromCString(errBuffer)
      throw CurlError.fromCode(code, details)
    }
  }

  def setUrl(URL: Ptr[CChar]): Unit = throwOnError(curl_easy_setopt_url(curl, CURLOPT_URL, URL))

  def setCustomRequest(request: Ptr[CChar]): Unit =
    throwOnError(curl_easy_setopt_customrequest(curl, CURLOPT_CUSTOMREQUEST, request))

  def setHttpHeader(
      headers: Ptr[curl_slist]
  ): Unit = throwOnError(curl_easy_setopt_httpheader(curl, CURLOPT_HTTPHEADER, headers))

  def setHttpVersion(
      version: CLong
  ): Unit = throwOnError(curl_easy_setopt_http_version(curl, CURLOPT_HTTP_VERSION, version))

  def setHeaderFunction(
      header_callback: header_callback
  ): Unit = throwOnError(
    curl_easy_setopt_headerfunction(curl, CURLOPT_HEADERFUNCTION, header_callback)
  )

  def setHeaderData(
      pointer: Ptr[Byte]
  ): Unit = throwOnError(curl_easy_setopt_headerdata(curl, CURLOPT_HEADERDATA, pointer))

  def setWriteFunction(
      write_callback: write_callback
  ): Unit = throwOnError(
    curl_easy_setopt_writefunction(curl, CURLOPT_WRITEFUNCTION, write_callback)
  )

  def setWriteData(
      pointer: Ptr[Byte]
  ): Unit = throwOnError(curl_easy_setopt_writedata(curl, CURLOPT_WRITEDATA, pointer))

  def setReadFunction(
      read_callback: read_callback
  ): Unit = throwOnError(curl_easy_setopt_readfunction(curl, CURLOPT_READFUNCTION, read_callback))

  def setReadData(
      pointer: Ptr[Byte]
  ): Unit = throwOnError(curl_easy_setopt_readdata(curl, CURLOPT_READDATA, pointer))

  def setProgressFunction(
      progress_callback: progress_callback
  ): Unit = throwOnError(
    curl_easy_setopt_xferinfofunction(curl, CURLOPT_XFERINFOFUNCTION, progress_callback)
  )

  def setProgressData(
      pointer: Ptr[Byte]
  ): Unit = throwOnError(curl_easy_setopt_xferinfodata(curl, CURLOPT_XFERINFODATA, pointer))

  def setUpload(value: Boolean): Unit =
    throwOnError(curl_easy_setopt_upload(curl, CURLOPT_UPLOAD, if (value) 1 else 0))

  def setVerbose(value: Boolean): Unit =
    throwOnError(curl_easy_setopt_verbose(curl, CURLOPT_VERBOSE, if (value) 1 else 0))

  def setWebsocket(
      flags: CLong
  ): Unit = throwOnError(curl_easy_setopt_websocket(curl, CURLOPT_WS_OPTIONS, flags))

  def setNoSignal(value: Boolean): Unit =
    throwOnError(curl_easy_setopt_no_signal(curl, CURLOPT_NOSIGNAL, if (value) 1 else 0))

  def setNoProgress(value: Boolean): Unit =
    throwOnError(curl_easy_setopt_noprogress(curl, CURLOPT_NOPROGRESS, if (value) 1 else 0))

  def wsSend(
      buffer: Ptr[Byte],
      bufLen: CSize,
      send: Ptr[CSize],
      fragsize: CSize,
      flags: UInt,
  ): Unit = throwOnError(curl_easy_ws_send(curl, buffer, bufLen, send, fragsize, flags))

  def wsMeta(): Ptr[curl_ws_frame] = curl_easy_ws_meta(curl)

  def pause(bitmask: CInt): Unit = throwOnError(curl_easy_pause(curl, bitmask))
}

/** An structured style of using curl_easy handle.
  * The handle can be used as the argument of the body function.
  * It is non-blocking but synchronous.
  * For having it async, you can wrap it in a Future.
  */
/*private[gurl]*/
object CurlEasy {
  final private val CURL_ERROR_SIZE = 256L

  def withEasy[T](body: CurlRuntimeContext ?=> CurlEasy => T)(using cc: CurlRuntimeContext): T =
    // Handle cleanup should be done by either the scheduler
    // or appended to this cleanUp function
    val handle = cc.getNewHandle()

    val zone: Zone = Zone.open()
    try {
      val buf = zone.alloc(CURL_ERROR_SIZE.toULong)

      val code = curl_easy_setopt_errorbuffer(handle, CURLOPT_ERRORBUFFER, buf)
      if (code.isError) {
        throw CurlError.fromCode(code)
      }

      body(CurlEasy(handle, buf))
    } finally zone.close()
}
