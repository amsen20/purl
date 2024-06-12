package gurl
package http

import gurl.internal.Utils
import gurl.unsafe.libcurl_const
import gurl.http.simple._
import gurl.unsafe.CurlRuntimeContext

import gears.async.Future
import scalanative.unsigned._
import scalanative.unsafe._
import scalanative.unsigned._
import scala.util.Success
import scala.util.Failure
import scala.collection.mutable.ArrayBuffer
import scala.scalanative.unsafe.CArray
import scala.util.Try
import gears.async.Async

private enum HeaderLine:
  case StatusLine(version: HttpVersion, status: Int)
  case Line(content: Array[Byte])
  case CRLF

final private[gurl] class RequestRecv {

  // Mutable shared state.
  val responseBody: ArrayBuffer[Byte] = ArrayBuffer[Byte]()
  val responseHeaders: ArrayBuffer[HeaderLine] = ArrayBuffer[HeaderLine]()
  val result: Future.Promise[SimpleResponse] = Future.Promise()

  @inline def response()(using Async): Try[SimpleResponse] =
    result.asFuture.awaitResult

  def parseResponse(headersList: List[HeaderLine]): Try[SimpleResponse] = {
    if headersList.isEmpty then return Failure(new Exception("Empty headers"))
    headersList.head match
      case HeaderLine.StatusLine(version, status) =>
        // Ensure it is the last status line
        if headersList.tail.exists {
            case HeaderLine.StatusLine(_, _) => true
            case _ => false
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

        val responseContent = synchronized(responseBody.toArray)

        Success(
          SimpleResponse(
            version,
            status,
            headers,
            trailers,
            responseContent,
          )
        )
      case _ => parseResponse(headersList.tail)
  }

  @inline def onTerminated(res: Option[Throwable]): Unit =
    if result.poll().isEmpty then
      res match
        case Some(e) => result.complete(Failure(e))
        case None => result.complete(parseResponse(synchronized(responseHeaders.toList)))

  @inline def onWrite(
      buffer: Ptr[CChar],
      size: CSize,
      nmemb: CSize,
  ): CSize =
    val amount = size * nmemb
    synchronized:
      Utils.appendBufferToArrayBuffer(buffer, responseBody, amount.toInt)
    amount

  @inline def onHeader(
      buffer: Ptr[CChar],
      size: CSize,
      nitems: CSize,
  ): CSize = {
    val content = ArrayBuffer[Byte]()
    Utils.appendBufferToArrayBuffer(buffer, content, size.toInt * nitems.toInt)
    val decoded = content.map(_.toChar).mkString
    val headerLine =
      if decoded == "\r\n" then HeaderLine.CRLF
      else if decoded.startsWith("HTTP/") then
        try {
          val List(v, c) = decoded.split(' ').toList.take(2)
          HeaderLine.StatusLine(HttpVersion.fromString(v).get, c.toInt)
        } catch {
          case e: Throwable =>
            result.complete(Failure(e))
            return size * nitems
        }
      else HeaderLine.Line(content.toArray)

    synchronized:
      responseHeaders += headerLine

    size * nitems
  }
}

private[gurl] object RequestRecv {
  def apply(): RequestRecv = new RequestRecv()

  private[gurl] def headerCallback(
      buffer: Ptr[CChar],
      size: CSize,
      nitems: CSize,
      userdata: Ptr[Byte],
  ): CSize =
    Utils.fromPtr[RequestRecv](userdata).onHeader(buffer, size, nitems)

  private[gurl] def writeCallback(
      buffer: Ptr[CChar],
      size: CSize,
      nmemb: CSize,
      userdata: Ptr[Byte],
  ): CSize =
    Utils.fromPtr[RequestRecv](userdata).onWrite(buffer, size, nmemb)

}
