package ca.uwaterloo.plg.curl
package unsafe

import ca.uwaterloo.plg.curl.CurlError

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext
import scala.concurrent.JavaConversions._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._
import java.util.concurrent.locks

/** The multi curl scheduler that polls periodically for new events.
  * The scheduler is located in a parallel thread.
  *
  * @param multiHandle
  * @param maxConcurrentConnections
  * @param maxConnections
  */
final private[curl] class CurlMultiScheduler(
    multiHandle: Ptr[libcurl.CURLM],
    maxConcurrentConnections: Int,
    maxConnections: Int,
) extends CurlRuntimeContext {

  private val gcRoot = new GCRoot()

  // Shared mutable state, should be accessed only in synchronized block
  private val callbacksBuffer = mutable.Map[Ptr[libcurl.CURL], Either[Throwable, Unit] => Unit]()
  private val callbacks = mutable.Map[Ptr[libcurl.CURL], Either[Throwable, Unit] => Unit]()

  @volatile private var running = true
  @volatile private var reason = Option.empty[Throwable]

  /** Main loop that polls for new events
    * and adds new handles to the multi handle
    * when there is space for them.
    */
  def loop(): Unit =
    while (synchronized(running)) {
      try poll(Duration.Inf)
      catch {
        case e =>
          synchronized:
            running = false
            reason = Some(e)
      }

      synchronized:
        while (callbacks.size < maxConcurrentConnections && callbacksBuffer.nonEmpty) {
          val (handle, cb) = callbacksBuffer.head
          callbacksBuffer.remove(handle)
          callbacks(handle) = cb

          val code = libcurl.curl_multi_add_handle(multiHandle, handle)
          if (code.isError)
            throw CurlError.fromMCode(code)
        }
    }
    cleanUp()

  def poll(timeout: Duration): Boolean = {
    val timeoutIsInf = timeout == Duration.Inf
    val noCallbacks = synchronized(callbacks.isEmpty)

    if (timeoutIsInf && noCallbacks) false
    else {
      val timeoutMillis =
        if (timeoutIsInf) Int.MaxValue else timeout.toMillis.min(Int.MaxValue).toInt

      if (timeout > Duration.Zero) {

        val pollCode = libcurl.curl_multi_poll(
          multiHandle,
          null,
          0.toUInt,
          timeoutMillis,
          null,
        )

        if (pollCode.isError)
          throw CurlError.fromMCode(pollCode)
      }

      if (noCallbacks) false
      else {
        val runningHandles = stackalloc[CInt]()
        val performCode = libcurl.curl_multi_perform(multiHandle, runningHandles)

        if (performCode.isError)
          throw CurlError.fromMCode(performCode)

        while ({
          val msgsInQueue = stackalloc[CInt]()
          val info = libcurl.curl_multi_info_read(multiHandle, msgsInQueue)

          if (info != null && synchronized(running)) {
            val curMsg = libcurl.curl_CURLMsg_msg(info)
            if (curMsg == libcurl_const.CURLMSG_DONE) {
              val handle = libcurl.curl_CURLMsg_easy_handle(info)
              synchronized:
                callbacks.remove(handle).foreach { cb =>
                  val result = libcurl.curl_CURLMsg_data_result(info)
                  cb(
                    if (result.isOk) Right(())
                    else Left(CurlError.fromCode(result))
                  )
                }

              val code = libcurl.curl_multi_remove_handle(multiHandle, handle)
              if (code.isError)
                throw CurlError.fromMCode(code)
              libcurl.curl_easy_cleanup(handle)
            }
            true
          } else false
        }) {}

        !runningHandles > 0
      }
    }
  }

  def stop(): Unit =
    synchronized:
      if (!running) return // already stopped

      running = false
      reason = Some(new RuntimeException("CurlMultiScheduler is not running anymore"))

      val code = libcurl.curl_multi_wakeup(multiHandle)
      if (code.isError)
        throw CurlError.fromMCode(code)

  /** Adds a curl handler that is expected to terminate
    * like a normal http request
    *
    * IMPORTANT NOTE: if you add a transfer that does not terminate (e.g. websocket) using this method,
    * application might hang, because those transfer don't seem to change state,
    * so it's not distinguishable whether they are finished or have other work to do
    *
    * @param handle curl easy handle to add
    * @param cb callback to run when this handler has finished its transfer
    */
  override def addHandle(handle: Ptr[libcurl.CURL], cb: Either[Throwable, Unit] => Unit): Unit =
    synchronized:
      if (reason.isDefined) cb(Left(reason.get))
      else
        if (callbacks.size >= maxConnections)
          cb(Left(new RuntimeException("number of connections exceeded the limit")))

        callbacksBuffer(handle) = cb

        if (callbacks.size < maxConcurrentConnections) {
          val code = libcurl.curl_multi_wakeup(multiHandle)
          if (code.isError)
            throw CurlError.fromMCode(code)
        }

  /** Keeps track of the object to prevent it from being garbage collected
    */
  override def keepTrack(obj: Object): Unit =
    gcRoot.addRoot(obj)

  /** Cleans up all the easy handles
    */
  def cleanUp(): Unit =
    (callbacks ++ callbacksBuffer).foreach { case (handle, cb) =>
      cb(Left(reason.get))

      val code = libcurl.curl_multi_remove_handle(multiHandle, handle)
      if (code.isError)
        throw CurlError.fromMCode(code)
      libcurl.curl_easy_cleanup(handle)
    }

    callbacks.clear()
    callbacksBuffer.clear()
}

private[curl] object CurlMultiScheduler {

  def getWithCleanUp(
      multiHandle: Ptr[libcurl.CURLM],
      maxConcurrentConnections: Int,
      maxConnections: Int,
  ): (CurlMultiScheduler, () => Unit) = {
    val scheduler = new CurlMultiScheduler(multiHandle, maxConcurrentConnections, maxConnections)

    // It is not a daemon thread,
    // because we want to wait for it to finish gracefully.
    val pollerThread = Thread(() => scheduler.loop())
    pollerThread.start()

    val cleanUp = () => {
      // Notify the poller to stop
      scheduler.stop()

      // Wait for the poller to finish
      // and clean up all the handles
      pollerThread.join()
    }

    (scheduler, cleanUp)
  }
}
