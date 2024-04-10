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
  )(using zone: Zone, cc: CurlRuntimeContext): Unit = {
    // TODO add in options
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

    println("the pointer still is: " + headers.list.toString())
    handle.setHttpHeader(headers.toPtr)
    println("the pointer and also still is: " + headers.list.toString())

    handle.setReadData(Utils.toPtr(sendData))
    handle.setReadFunction(RequestSend.readCallback(_, _, _, _))

    handle.setHeaderData(Utils.toPtr(recvData))
    handle.setHeaderFunction(RequestRecv.headerCallback(_, _, _, _))

    handle.setWriteData(Utils.toPtr(recvData))
    handle.setWriteFunction(RequestRecv.writeCallback(_, _, _, _))

    cc.addHandle(handle.curl, recvData.onTerminated)
  }

  def apply(req: SimpleRequest)(using CurlRuntimeContext)(using Async): Try[SimpleResponse] =
    // gc <- GCRoot()
    println("apply begin")
    var r = try {
      CurlEasy.withEasy { handle =>
        val sendData = RequestSend(req.body)
        val recvData = RequestRecv()
        given zone: Zone = Zone.open()
        println("before CurlSList.withSList")
        val rr = try {
          CurlSList.withSList(headers =>
            println("the slist pointer beginning with slist" + toPtr(headers).toString())
            println("inside begin CurlSList.withSList")
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
            // somehow send the body
            val rrr = recvData.response()
            println("the slist pointer after response in with slist" + toPtr(headers).toString())
            println("the slist pointer" + toPtr(headers).toString())
            println("theeeeeee pppppointer and also still is: " + headers.list.toString())
            rrr
          )
        } finally zone.close()
        println("after CurlSList.withSList")
        rr
      }
    } catch {
      case e: Exception => Failure(e)
    }
    println("apply end")
    r
    // flow <- FlowControl(handle)
    // _ <- gc.add(send, recv)
    // _ <- setup(handle, ec, send, recv, req)
    // _ <- req.body.through(send.pipe).compile.drain.background
    // resp <- recv.response()
  // yield resp
}
