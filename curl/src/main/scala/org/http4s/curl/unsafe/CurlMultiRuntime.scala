package org.http4s.curl
package unsafe

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.scalanative.unsafe._
import org.http4s.curl.unsafe.libcurl.CURLM

/**
  * The runtime context for the curl multi interface
  * It allows to run multiple easy handles non-blocking and concurrently.
  * When the runtime is finished, all the easy handles are closed as well.
  */
private[curl] object CurlMultiRuntime extends CurlRuntime {

  def apply[T](body: CurlRuntimeContext ?=> T) =
    var multiHandle: Ptr[CURLM] = null
    var scheduler: CurlMultiScheduler = null
    var schedulerCleanUp: () => Unit = null

    try {
      val initCode = libcurl.curl_global_init(2)
      if (initCode.isError)
        throw CurlError.fromCode(initCode)

      multiHandle = libcurl.curl_multi_init()
      if (multiHandle == null)
        throw new RuntimeException("curl_multi_init")

      val schedulerShutDown = CurlMultiScheduler.getWithCleanUp(multiHandle, 64)
      scheduler = schedulerShutDown._1
      schedulerCleanUp = schedulerShutDown._2

      body(using scheduler)

    } finally {
      if (schedulerCleanUp != null && multiHandle != null)
        schedulerCleanUp()

      val code =
        if multiHandle != null then libcurl.curl_multi_cleanup(multiHandle) else CURLMcode(0)

      libcurl.curl_global_cleanup()
      if (multiHandle != null && code.isError)
        throw CurlError.fromMCode(code)
    }
}