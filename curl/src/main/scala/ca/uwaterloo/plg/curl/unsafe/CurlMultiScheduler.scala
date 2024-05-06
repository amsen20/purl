package ca.uwaterloo.plg.curl
package unsafe

import ca.uwaterloo.plg.curl.CurlError

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext
import scala.concurrent.JavaConversions._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

/** The multi curl scheduler that polls periodically for new events.
  * The scheduler is located in a parallel thread.
  *
  * @param multiHandle
  * @param pollEvery
  */
final private[curl] class CurlMultiScheduler(multiHandle: Ptr[libcurl.CURLM], pollEvery: Int)
    extends CurlRuntimeContext {

  // Shared mutable state, should be accessed only in synchronized block
  private val callbacks = mutable.Map[Ptr[libcurl.CURL], Either[Throwable, Unit] => Unit]()

  @volatile private var running = true
  @volatile private var exception = Option.empty[Throwable]

  // Main loop that polls for new events
  def loop(): Unit =
    while (synchronized(running)) {
      val until = System.currentTimeMillis() + pollEvery

      try
        poll(Duration(until - System.currentTimeMillis(), "ms"))
      catch {
        case e =>
          synchronized:
            callbacks.foreach(_._2(Left(e)))
            callbacks.clear()
            exception = Some(e)
            running = false
      }

      val sleepAmount = until - System.currentTimeMillis()
      if sleepAmount > 0 then Thread.sleep(until - System.currentTimeMillis())
    }

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

          if (info != null) {
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
  override def addHandle(handle: Ptr[libcurl.CURL], cb: Either[Throwable, Unit] => Unit): Unit = {
    val code = libcurl.curl_multi_add_handle(multiHandle, handle)
    if (code.isError)
      throw CurlError.fromMCode(code)

    synchronized:
      if (exception.isDefined)
        cb(Left(exception.get))
      else
        callbacks(handle) = cb
  }

  /** Cleans up all the easy handles
    */
  override def cleanUp(): Unit =
    synchronized:
      callbacks.foreach { case (handle, cb) =>
        callbacks.remove(handle)
        cb(Left(new RuntimeException("CurlMultiScheduler is shutting down")))

        val code = libcurl.curl_multi_remove_handle(multiHandle, handle)
        if (code.isError)
          throw CurlError.fromMCode(code)
        libcurl.curl_easy_cleanup(handle)
      }
}

private[curl] object CurlMultiScheduler {

  def getWithCleanUp(
      multiHandle: Ptr[libcurl.CURLM],
      pollEvery: Int,
  ): (CurlMultiScheduler, () => Unit) = {
    val scheduler = new CurlMultiScheduler(multiHandle, pollEvery)

    // It is not a daemon thread,
    // because we want to wait for it to finish gracefully.
    val pollerThread = Thread(() => scheduler.loop())
    pollerThread.start()

    val cleanUp = () => {
      synchronized:
        scheduler.running = false
      scheduler.cleanUp()
    }

    (scheduler, cleanUp)
  }
}
