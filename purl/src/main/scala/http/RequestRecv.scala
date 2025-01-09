package purl
package http

import pollerBear.logger.PBLogger
import purl.http.simple._
import purl.internal.FastNativeString
import purl.internal.Utils
import purl.unsafe.libcurl_const
import purl.unsafe.CurlRuntimeContext
import scala.collection.mutable.ArrayBuffer
import scala.scalanative.unsafe.CArray
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scalanative.unsafe._
import scalanative.unsigned._

private enum HeaderLine:
  case StatusLine(version: HttpVersion, status: Int)
  case Line(content: String)
  case CRLF

final private[purl] class RequestRecv(onResponse: Try[SimpleResponse] => Unit) {

  // Mutable shared state.
  var responseBody: FastNativeString           = FastNativeString()
  val responseHeaders: ArrayBuffer[HeaderLine] = ArrayBuffer[HeaderLine]()

  @volatile var isDone = false

  def parseResponse(headersList: List[HeaderLine]): Try[SimpleResponse] = {
    if headersList.isEmpty then return Failure(new Exception("Empty headers"))
    headersList.head match
      case HeaderLine.StatusLine(version, status) =>
        // Ensure it is the last status line
        if headersList.tail.exists {
            case HeaderLine.StatusLine(_, _) => true
            case _                           => false
          }
        then return parseResponse(headersList.tail)

        val CLRFIndex = headersList.indexOf(HeaderLine.CRLF)
        if CLRFIndex == -1 then return Failure(new Exception("No CRLF found"))
        val headers = headersList.slice(1, CLRFIndex).collect { case HeaderLine.Line(content) =>
          content
        }
        val trailers = headersList.slice(CLRFIndex + 1, headersList.length).collect {
          case HeaderLine.Line(content) => content
        }

        val responseContent = responseBody

        Success(
          SimpleResponse(
            version,
            status,
            headers,
            trailers,
            responseContent
          )
        )
      case _ => parseResponse(headersList.tail)
  }

  @inline def onTerminated(res: Option[Throwable]): Unit =
    if !isDone then
      res match
        case Some(e) => onResponse(Failure(e))
        case None    => onResponse(parseResponse(responseHeaders.toList))
      isDone = true

  @inline def onWrite(
      buffer: Ptr[CChar],
      size: CSize,
      nmemb: CSize
  ): CSize =
    val amount = size * nmemb
    PBLogger.log("Before receiving content to buffer")
    responseBody.append(buffer, amount.toInt)
    PBLogger.log(s"!!!!!!!!!!!!!Received content ${amount} bytes")

    amount

  @inline def onHeader(
      buffer: Ptr[CChar],
      size: CSize,
      nitems: CSize
  ): CSize = {
    val decoded = fromCString(buffer)
    val headerLine =
      if decoded == "\r\n" then HeaderLine.CRLF
      else if decoded.startsWith("HTTP/") then
        try {
          val List(v, c) = decoded.split(' ').toList.take(2)
          HeaderLine.StatusLine(HttpVersion.fromString(v).get, c.toInt)
        } catch {
          case e: Throwable =>
            onResponse(Failure(e))
            isDone = true
            return size * nitems
        }
      else HeaderLine.Line(decoded)

    responseHeaders += headerLine
    PBLogger.log(s"!!!!!!!!!!!!!Received header: ${headerLine}")

    size * nitems
  }

}

private[purl] object RequestRecv {
  def apply(onResponse: Try[SimpleResponse] => Unit): RequestRecv = new RequestRecv(onResponse)

  private[purl] def headerCallback(
      buffer: Ptr[CChar],
      size: CSize,
      nitems: CSize,
      userdata: Ptr[Byte]
  ): CSize =
    Utils.fromPtr[RequestRecv](userdata).onHeader(buffer, size, nitems)

  private[purl] def writeCallback(
      buffer: Ptr[CChar],
      size: CSize,
      nmemb: CSize,
      userdata: Ptr[Byte]
  ): CSize =
    Utils.fromPtr[RequestRecv](userdata).onWrite(buffer, size, nmemb)

}
