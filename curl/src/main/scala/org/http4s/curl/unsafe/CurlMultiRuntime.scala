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
package unsafe

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.scalanative.unsafe._
import org.http4s.curl.unsafe.libcurl.CURLM

object CurlMultiRuntime extends CurlRuntime {

  def apply[T](body: CurlRuntimeContext ?=> T) =
    var multiHandle: Ptr[CURLM] = null
    var scheduler: CurlExecutorScheduler = null
    var schedulerCleanUp: () => Unit = null

    try {
      val initCode = libcurl.curl_global_init(2)
      if (initCode.isError)
        throw CurlError.fromCode(initCode)
      
      multiHandle = libcurl.curl_multi_init()
      if (multiHandle == null)
        throw new RuntimeException("curl_multi_init")
      
      val schedulerShutDown = CurlExecutorScheduler.getWithCleanUp(multiHandle, 64)
      scheduler = schedulerShutDown._1
      schedulerCleanUp = schedulerShutDown._2

      body(using scheduler)
    
    } finally {
      if (schedulerCleanUp != null && multiHandle != null)
        schedulerCleanUp()
      
      val code = if multiHandle != null then libcurl.curl_multi_cleanup(multiHandle) else CURLMcode(0)
      
      libcurl.curl_global_cleanup()
      if (multiHandle != null && code.isError)
        throw CurlError.fromMCode(code)
    }
}
