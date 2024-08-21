package gurl
package multi

import gurl.unsafe._
import gurl.unsafe.libcurl.CURLM
import pollerBear.epoll._
import pollerBear.logger.PBLogger
import pollerBear.runtime._
import scala.concurrent.ExecutionContext
import scala.scalanative.posix.sched
import scala.scalanative.unsafe._

/**
 * The runtime context for the curl multi interface
 * It allows to run multiple easy handles non-blocking and concurrently.
 * When the runtime is finished, all the easy handles are closed as well.
 * The creation of the multi runtime is blocking, and it waits until:
 * - an error occurs
 * - the body execution is finished
 */
object CurlMultiRuntime extends CurlRuntime {

  def apply[T](maxConcurrentConnections: Int, maxConnections: Int)(
      using Poller
  )(body: CurlRuntimeContext ?=> T) =
    var multiHandle: Ptr[CURLM] = null
    var cmc: CurlMultiPBContext = null

    try {
      val initCode = libcurl.curl_global_init(2)
      if (initCode.isError)
        throw CurlError.fromCode(initCode)

      multiHandle = libcurl.curl_multi_init()
      if (multiHandle == null)
        throw new RuntimeException("curl_multi_init")

      cmc = CurlMultiPBContext.getCurlMultiContext(
        multiHandle,
        maxConcurrentConnections,
        maxConnections
      )

      body(
        using cmc
      )
    } finally {
      if (cmc != null && multiHandle != null)
        PBLogger.log("cleaning up the curl multi runtime")
        cmc.cleanUp()
        PBLogger.log("done cleaning up the curl multi runtime")

      val code =
        if multiHandle != null then libcurl.curl_multi_cleanup(multiHandle) else CURLMcode(0)

      if (multiHandle != null && code.isError)
        throw CurlError.fromMCode(code)
      libcurl.curl_global_cleanup()
    }

}
