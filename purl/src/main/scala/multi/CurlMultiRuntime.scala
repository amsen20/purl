package purl
package multi

import pollerBear.epoll._
import pollerBear.logger.PBLogger
import pollerBear.runtime._
import purl.global.CurlGlobalContext
import purl.unsafe._
import purl.unsafe.libcurl.CURLM
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

  def apply[T](
      using CurlGlobalContext,
      Poller
  )(body: CurlRuntimeContext ?=> T) =
    @volatile var multiHandle: Ptr[CURLM] = null
    @volatile var cmc: CurlMultiPBContext = null

    def cleanUpMultiHandle() =
      val code =
        if multiHandle != null then libcurl.curl_multi_cleanup(multiHandle) else CURLMcode(0)

      if (multiHandle != null && code.isError)
        CurlError.fromMCode(code).printStackTrace()

    try {
      multiHandle = libcurl.curl_multi_init()
      if (multiHandle == null)
        throw new RuntimeException("curl_multi_init")

      cmc = CurlMultiPBContext.getCurlMultiContext(
        multiHandle
      )

      body(
        using cmc
      )
    } catch {
      case e: Throwable =>
        throw e
    } finally
      if (cmc != null && multiHandle != null)
        PBLogger.log("cleaning up the curl multi runtime")
        cmc.cleanUp(cleanUpMultiHandle)
        PBLogger.log("done cleaning up the curl multi runtime")
      else if (multiHandle != null)
        cleanUpMultiHandle()

}
