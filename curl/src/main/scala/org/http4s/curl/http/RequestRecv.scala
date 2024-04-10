/*
 * Copyright 2022 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.curl.http

import org.http4s.curl.internal.Utils
import org.http4s.curl.unsafe.libcurl_const
import org.http4s.curl.http.simple._

import gears.async.Future

import scalanative.unsigned._
import scalanative.unsafe._
import scalanative.unsigned._
import org.http4s.curl.unsafe.CurlRuntimeContext
import scala.util.Success
import scala.util.Failure
import scala.collection.mutable.ArrayBuffer
import scala.scalanative.unsafe.CArray
import scala.util.Try
import gears.async.Async

private enum HeaderLine:
  case StatusLine(version: HttpVersion, status: Int)
  case Line(content: String)
  case CRLF

final private[curl] class RequestRecv {
  val responseBody: ArrayBuffer[Byte] = ArrayBuffer[Byte]()
  val responseHeaders: ArrayBuffer[HeaderLine] = ArrayBuffer[HeaderLine]()
  @volatile var responseContent: SimpleResponse = null
  val result: Future.Promise[SimpleResponse] = Future.Promise()
  // @inline val responseBody: Stream[IO, Byte] = Stream
  //   .repeatEval(
  //     // sequencing is important! the docs for `curl_easy_pause` say:
  //     // > When this function is called to unpause receiving,
  //     // > the chance is high that you will get your write callback called before this function returns.
  //     // so it's important to indicate that the queue is ready before unpausing recv
  //     responseBodyQueueReady.set(true).to[IO] *> flowControl.unpauseRecv *> responseBodyQueue.take
  //   )
  //   .unNoneTerminate
  //   .map(Chunk.byteVector(_))
  //   .unchunks

  @inline def response()(using Async): Try[SimpleResponse] = 
    println("response begin")
    val r = result.asFuture.awaitResult
    println("response end")
    r
  
  def parseResponse(headersList: List[HeaderLine]): Try[SimpleResponse] = {
    if headersList.isEmpty then
      return Failure(new Exception("Empty headers"))
    headersList.head match
      case HeaderLine.StatusLine(version, status) =>
        // Ensure it is the last status line
        if headersList.tail.exists {
          case HeaderLine.StatusLine(_, _) => true
          case _ => false
        } then
          return parseResponse(headersList.tail)
        
        val CLRFIndex = headersList.indexOf(HeaderLine.CRLF)
        if CLRFIndex == -1 then
          return Failure(new Exception("No CRLF found"))
        val headers = headersList.slice(1, CLRFIndex).collect {
          case HeaderLine.Line(content) => content
        }
        val trailers = headersList.slice(CLRFIndex + 1, headersList.length).collect {
          case HeaderLine.Line(content) => content
        }
        
        val responseContent = responseBody.map(_.toChar).mkString
        
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

  @inline def onTerminated(res: Either[Throwable, Unit]): Unit = {
    println("OnTerminated begin")
    if result.poll().isEmpty then
      res match
        case Left(ex) => result.complete(Failure(ex))
        case Right(_) => result.complete(parseResponse(responseHeaders.toList))
    println("OnTerminated end")
  }
    
  @inline def onWrite(
      buffer: Ptr[CChar],
      size: CSize,
      nmemb: CSize,
  ): CSize =
    println("OnWrite begin")
    val amount = size * nmemb
    Utils.appendBufferToArrayBuffer(buffer, responseBody, amount.toInt)
    println("OnWrite end")
    amount
  
  @inline def onHeader(
      buffer: Ptr[CChar],
      size: CSize,
      nitems: CSize,
  ): CSize = {
    println("OnHeader begin")
    val content = ArrayBuffer[Byte]()
    Utils.appendBufferToArrayBuffer(buffer, content, size.toInt * nitems.toInt)
    val decoded = content.map(_.toChar).mkString
    val headerLine = if decoded == "\r\n" then 
      HeaderLine.CRLF
    else if decoded.startsWith("HTTP/") then
      try {
        val List(v, c) = decoded.split(' ').toList.take(2)
        HeaderLine.StatusLine(HttpVersion.fromString(v).get, c.toInt)
      } catch {
        case e: Throwable => 
          result.complete(Failure(e))
          return size * nitems
      }
    else HeaderLine.Line(decoded)

    responseHeaders += headerLine

    println("OnHeader end")
    size * nitems
  }
//     val decoded = ByteVector
//       .view(buffer, nitems.toLong)
//       .decodeAscii
//       .liftTo[IO]

//     def parseHeader(header: String): IO[Header.Raw] =
//       header.dropRight(2).split(": ") match {
//         case Array(name, value) => IO.pure(Header.Raw(CIString(name), value))
//         case _ => IO.raiseError(new RuntimeException("header_callback"))
//       }

//     val go = responseD.tryGet
//       .map(_.isEmpty)
//       .ifM(
//         // prelude
//         responseBuilder.get
//           .flatMap {
//             case None =>
//               decoded.map(_.split(' ')).flatMap {
//                 case Array(v, c, _*) =>
//                   for {
//                     version <- HttpVersion.fromString(v).liftTo[IO]
//                     status <- IO(c.toInt).flatMap(Status.fromInt(_).liftTo[IO])
//                     _ <- responseBuilder.set(Some(Response[IO](status, version)))
//                   } yield ()
//                 case _ => IO.raiseError(new RuntimeException("header_callback"))
//               }
//             case Some(wipResponse) =>
//               decoded.flatMap {
//                 case "\r\n" => responseD.complete(Right(wipResponse))
//                 case header =>
//                   parseHeader(header)
//                     .flatMap(h => responseBuilder.set(Some(wipResponse.putHeaders(h))))
//               }
//           }
//           .onError(ex => responseD.complete(Left(ex)).void),

//         // trailers
//         done.tryGet
//           .flatMap {
//             case Some(result) =>
//               trailerHeadersBuilder.get
//                 .flatMap(h => trailerHeaders.complete(result.as(h)))
//             case None =>
//               decoded.flatMap { header =>
//                 parseHeader(header)
//                   .flatMap(h => trailerHeadersBuilder.update(_.put(h)))
//               }
//           }
//           .onError(ex => trailerHeaders.complete(Left(ex)).void),
//       )

//     dispatcher.unsafeRunAndForget(go)

//     size * nitems
//   }
// }
}

private[curl] object RequestRecv {
  def apply(): RequestRecv = new RequestRecv()

  private[curl] def headerCallback(
      buffer: Ptr[CChar],
      size: CSize,
      nitems: CSize,
      userdata: Ptr[Byte],
  ): CSize =
    Utils.fromPtr[RequestRecv](userdata).onHeader(buffer, size, nitems)

  private[curl] def writeCallback(
      buffer: Ptr[CChar],
      size: CSize,
      nmemb: CSize,
      userdata: Ptr[Byte],
  ): CSize =
    Utils.fromPtr[RequestRecv](userdata).onWrite(buffer, size, nmemb)

}
