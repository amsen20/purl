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

package org.http4s.curl

import org.http4s.curl.http.CurlRequest
import org.http4s.curl.unsafe.CurlMultiRuntime
import org.http4s.curl.http.simple.SimpleRequest
import org.http4s.curl.http.simple.HttpVersion
import org.http4s.curl.http.simple.HttpMethod

import scala.util.Success
import gears.async.default.given
import scala.concurrent.ExecutionContext
import gears.async.Async
import scala.util.Failure

class CurlRequestSuite extends munit.FunSuite {

  given ExecutionContext = ExecutionContext.global

  test("simple get request") {
    Async.blocking:
      CurlMultiRuntime:
        CurlRequest(SimpleRequest(
          HttpVersion.V1_0,
          HttpMethod.GET,
          List(),
          "http://localhost:8080/http",
          ""
        )) match
          case Success(response) => 
            assert(response.body.nonEmpty)
          case Failure(exception) => 
            fail("couldn't get the response", exception)
  }
      
  test("status code") {
    Async.blocking:
      CurlMultiRuntime:
        for statusCode <- List(404, 500) do
          CurlRequest(SimpleRequest(
            HttpVersion.V1_0,
            HttpMethod.GET,
            List(),
            "http://localhost:8080/http/" + statusCode.toString,
            ""
          )) match
            case Success(response) => 
              assertEquals(response.status, statusCode)
            case Failure(exception) => 
              fail("couldn't get the response", exception)
  }

  test("error") {
    Async.blocking:
      CurlMultiRuntime:
        CurlRequest(SimpleRequest(
          HttpVersion.V1_0,
          HttpMethod.GET,
          List(),
          "unsupported://server",
          ""
        )) match
          case Success(response) => 
            fail("should have failed")
          case Failure(exception) => 
            println(exception.toString())
            assert(exception.isInstanceOf[CurlError])
  }

  test("post echo") {
    Async.blocking:
      CurlMultiRuntime:
        for msg <- List("a") do
          println("Current time: " + System.currentTimeMillis())
          CurlRequest(SimpleRequest(
            HttpVersion.V1_0,
            HttpMethod.POST,
            List(),
            "http://localhost:8080/http/echo",
            msg
          )) match
            case Success(response) => 
              assert(response.body.contains(msg))
            case Failure(exception) =>
              println(exception.toString())
              fail("couldn't get the response", exception)
  }
}
