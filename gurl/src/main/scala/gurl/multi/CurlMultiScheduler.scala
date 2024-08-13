package gurl
package multi

import gurl.logger.GLogger
import gurl.CurlError
import gurl.unsafe._
import gurl.unsafe.libcurl
import gurl.internal.Utils

import epoll._

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._
import java.util.concurrent.locks
import scala.scalanative.posix.signal
import epoll.Epoll
import gurl.unsafe.libcurl.CURL
import scala.annotation.switch

/** The multi curl scheduler that polls periodically for new events.
  * The scheduler is located in a parallel thread.
  *
  * @param multiHandle
  * @param maxConcurrentConnections
  * @param maxConnections
  */
final private[gurl] class CurlMultiScheduler(
    multiHandle: Ptr[libcurl.CURLM],
    epoll: Epoll,
    maxConcurrentConnections: Int,
    maxConnections: Int,
) extends CurlRuntimeContext {

  private val gcRoot = new GCRoot()

  // Shared mutable state, should be accessed only in synchronized block
  private val callbacksBuffer = mutable.Map[Ptr[libcurl.CURL], Option[Throwable] => Unit]()
  private val callbacks = mutable.Map[Ptr[libcurl.CURL], Option[Throwable] => Unit]()
  private val removedHandles = mutable.Set[Ptr[libcurl.CURL]]()

  // Pool of handles that are not used, this is for reusing the easy handles
  private val handlePool = mutable.Queue[Ptr[libcurl.CURL]]()

  private val expectFromFd = mutable.Map[Int, Long]()

  @volatile private var running = true
  @volatile private var reason = Option.empty[Throwable]

  @volatile private var deadline = -1

  /** Main loop that polls for new events
    * and removes handles from the multi handle
    * and then adds new handles to the multi handle
    * when there is space for them.
    */
  def loop(): Unit =
    start()

    while (synchronized(running)) {
      GLogger.log("looping...")
      try poll()
      catch {
        case e =>
          GLogger.log("exception caught %s".format(e.getMessage()))
          synchronized:
            running = false
            reason = Some(e)
      }

      synchronized:
        // Remove handles that are already deleted
        // from the list of callbacks, but they are still
        // (must be) in the multi handle.
        GLogger.log("cleaning up removed handles...")
        removedHandles.foreach(handle =>
          val code = libcurl.curl_multi_remove_handle(multiHandle, handle)
          if code.isError then throw CurlError.fromMCode(code)

          handlePool.enqueue(handle)
        )
        removedHandles.clear()

        // Add new handles to the multi handle from
        // the callback buffer.
        GLogger.log("adding handles...")
        while (callbacks.size < maxConcurrentConnections && callbacksBuffer.nonEmpty) {
          val (handle, cb) = callbacksBuffer.head
          callbacksBuffer.remove(handle)
          callbacks(handle) = cb

          GLogger.log("adding a new handle...")
          val code = libcurl.curl_multi_add_handle(multiHandle, handle)
          if (code.isError)
            throw CurlError.fromMCode(code)
        }
    }

    GLogger.log("cleaning up...")
    cleanUp()

  def poll(): Unit = {

    val timeoutIsInf = synchronized(deadline == -1)
    val timeout =
      if timeoutIsInf then -1
      else {
        val now = System.currentTimeMillis()
        val diff = deadline - now
        if diff < 0 then 0 else diff
      }
    val noCallbacks = synchronized(callbacks.isEmpty)

    val runningHandles = stackalloc[CInt]()

    if (!timeoutIsInf || !noCallbacks) {
      GLogger.log("entering epolling...")
      val (events, didTimeout) = epoll.waitForEvents(timeout.toInt)
      if (didTimeout) {
        GLogger.log("timeout happened")
        val actionCode = libcurl.curl_multi_socket_action(
          multiHandle,
          libcurl_const.CURL_SOCKET_TIMEOUT,
          0,
          runningHandles,
        )
        if (actionCode.isError)
          throw CurlError.fromMCode(actionCode)
      } else {
        GLogger.log("some events happened")
        events.foreach { waitEvent =>
          GLogger.log("processing an event...")
          var what = 0
          if waitEvent.events.isInput then what |= libcurl_const.CURL_CSELECT_IN
          if waitEvent.events.isOutput then what |= libcurl_const.CURL_CSELECT_OUT
          if waitEvent.events.isError then what |= libcurl_const.CURL_CSELECT_ERR

          if (synchronized(running))
            val actionCode =
              libcurl.curl_multi_socket_action(multiHandle, waitEvent.fd, what, runningHandles)
            if (actionCode.isError)
              throw CurlError.fromMCode(actionCode)
        }
      }
      GLogger.log("waken up from polling...")

      if (!noCallbacks && synchronized(running)) {
        while ({
          GLogger.log("looking for messages...")
          val msgsInQueue = stackalloc[CInt]()
          val info = libcurl.curl_multi_info_read(multiHandle, msgsInQueue)

          if (info != null && synchronized(running)) {
            GLogger.log("a new message!")
            val curMsg = libcurl.curl_CURLMsg_msg(info)
            if (curMsg == libcurl_const.CURLMSG_DONE) {
              GLogger.log("a request is done!")
              val handle = libcurl.curl_CURLMsg_easy_handle(info)
              synchronized:
                callbacks.remove(handle).foreach { cb =>
                  val result = libcurl.curl_CURLMsg_data_result(info)
                  cb(
                    if (result.isOk) None
                    else Some(CurlError.fromCode(result))
                  )
                }

                val code = libcurl.curl_multi_remove_handle(multiHandle, handle)
                if (code.isError)
                  throw CurlError.fromMCode(code)
                GLogger.log("removed the handle from the multi handle")
                handlePool.enqueue(handle)
                GLogger.log("cleaned up the easy handle")
                // Should deletes the handle from removed list,
                // because if the list is in the list then, the callback
                // is removed so it is not called and now here, the handle
                // is cleaned up.
                removedHandles.remove(handle)
            }
            true
          } else false
        }) {}
      }
    }
  }

  def start(): Unit =
    GLogger.log("starting the scheduler...")
    val runningHandles = stackalloc[CInt]()
    val actionCode = libcurl.curl_multi_socket_action(
      multiHandle,
      libcurl_const.CURL_SOCKET_TIMEOUT,
      0,
      runningHandles,
    )
    if (actionCode.isError)
      throw CurlError.fromMCode(actionCode)

  def stop(): Unit =
    GLogger.log("stopping the scheduler...")
    synchronized:
      if (!running) return // already stopped
      GLogger.log("the scheduler is running")

      running = false
      GLogger.log("set running to false")
      reason = Some(new RuntimeException("CurlMultiScheduler is not running anymore"))

      GLogger.log("waking up the poller thread...")
      epoll.wakeUp()
      GLogger.log("wake up signal sent")

    GLogger.log("done stopping the scheduler!")

  override def addHandle(handle: Ptr[libcurl.CURL], cb: Option[Throwable] => Unit): Unit =
    synchronized:
      if (reason.isDefined) cb(Some(reason.get))
      else
        if (callbacks.size >= maxConnections)
          cb(Some(new RuntimeException("number of connections exceeded the limit")))

        callbacksBuffer(handle) = cb

        if (callbacks.size < maxConcurrentConnections) {
          epoll.wakeUp()
        }

  override def removeHandle(handle: Ptr[libcurl.CURL]): Unit =
    synchronized:
      val cb = callbacks.remove(handle)
      if cb.isDefined then removedHandles.add(handle)
      val cbb = callbacksBuffer.remove(handle)

      if cb.isDefined || cbb.isDefined then epoll.wakeUp()

  override def getNewHandle(): Ptr[libcurl.CURL] =
    synchronized:
      if handlePool.nonEmpty then
        val handle = handlePool.dequeue()
        libcurl.curl_easy_reset(handle)
        handle
      else
        val handle = libcurl.curl_easy_init()
        if (handle == null)
          throw new RuntimeException("curl_easy_init")
        handle

  /** Keeps track of the object to prevent it from being garbage collected
    */
  override def keepTrack(obj: Object): Unit =
    gcRoot.add(obj)

  /** Forgets the object to allow it to be garbage collected
    */
  override def forget(obj: Object): Unit =
    gcRoot.remove(obj)

  override def monitorProgress(
      dltotal: CLongLong,
      dlnow: CLongLong,
      ultotal: CLongLong,
      ulnow: CLongLong,
  ): CInt =
    if (!synchronized(running)) 1
    else 0

  def setIfChanged(fd: Int, currentExpectMask: Long, newExpect: EpollEvents): Unit =
    val newExpectMask = newExpect.getMask().toLong
    if currentExpectMask != newExpectMask then
      expectFromFd(fd) = newExpectMask
      if currentExpectMask == -1 then epoll.addFd(fd, newExpect)
      else epoll.modifyFd(fd, newExpect)

  override def expectSocket(easy: Ptr[CURL], socketFd: CInt, what: CInt): CInt =
    GLogger.log(s"expecting socket on fd: ${socketFd}...")
    val currentExpectationFromFdOption = expectFromFd.get(socketFd)
    val currentExpectationFromFd = currentExpectationFromFdOption.getOrElse(-1L)

    what match {
      case libcurl_const.CURL_POLL_IN =>
        GLogger.log("poll in")
        setIfChanged(socketFd, currentExpectationFromFd, EpollInputEvents().input())
      case libcurl_const.CURL_POLL_OUT =>
        GLogger.log("poll out")
        setIfChanged(socketFd, currentExpectationFromFd, EpollInputEvents().output())
      case libcurl_const.CURL_POLL_INOUT =>
        GLogger.log("poll inout")
        setIfChanged(socketFd, currentExpectationFromFd, EpollInputEvents().input().output())
      case libcurl_const.CURL_POLL_REMOVE =>
        GLogger.log("poll remove")
        expectFromFd.remove(socketFd)
        epoll.removeFd(socketFd)
      case _ =>
      // Handle other cases here
    }

    0 // success

  override def expectTimer(Ctimeout: CLong): CInt =
    GLogger.log("expecting timer...")
    val timeout = Ctimeout.toLong
    val now = System.currentTimeMillis()
    synchronized {
      deadline = if timeout == -1 then -1 else (now + timeout).toInt
    }

    0 // success

  /** Cleans up all the easy handles
    */
  def cleanUp(): Unit =
    // Inform their callbacks that the
    // polling is stopped.
    GLogger.log("informing callbacks...")
    (callbacks ++ callbacksBuffer).foreach(_._2(reason))

    // Remove the handles from multi handle for
    // terminating all connections and cleaning multi handle
    // peacefully.
    GLogger.log("cleaning up handles...")
    (callbacks.map(_._1) ++ removedHandles ++ handlePool).foreach { case handle =>
      val code = libcurl.curl_multi_remove_handle(multiHandle, handle)
      if (code.isError)
        throw CurlError.fromMCode(code)
      libcurl.curl_easy_cleanup(handle)
    }

    callbacks.clear()
    callbacksBuffer.clear()
    removedHandles.clear()
}

private[gurl] object CurlMultiScheduler {

  def setUpCallbacks(scheduler: CurlMultiScheduler, multiHandle: Ptr[libcurl.CURLM]): Unit =
    val multiSocket = MultiSocket(scheduler.asInstanceOf[CurlRuntimeContext])
    scheduler.keepTrack(multiSocket)

    // Socket data:
    val socketDataCode = libcurl.curl_multi_setopt_socket_data(
      multiHandle,
      libcurl_const.CURLMOPT_SOCKETDATA,
      Utils.toPtr(multiSocket),
    )
    if (socketDataCode.isError)
      throw CurlError.fromMCode(socketDataCode)

    // Socket callback:
    val socketCallbackCode = libcurl.curl_multi_setopt_socket_function(
      multiHandle,
      libcurl_const.CURLMOPT_SOCKETFUNCTION,
      MultiSocket.socketCallback(_, _, _, _, _),
    )
    if (socketCallbackCode.isError)
      throw CurlError.fromMCode(socketCallbackCode)

    val multiTimer = MultiTimer(scheduler.asInstanceOf[CurlRuntimeContext])
    scheduler.keepTrack(multiTimer)

    // Timer data:
    val timerDataCode = libcurl.curl_multi_setopt_timer_data(
      multiHandle,
      libcurl_const.CURLMOPT_TIMERDATA,
      Utils.toPtr(multiTimer),
    )

    // Timer callback:
    val timerCallbackCode = libcurl.curl_multi_setopt_timer_function(
      multiHandle,
      libcurl_const.CURLMOPT_TIMERFUNCTION,
      MultiTimer.timerCallback(_, _, _),
    )
    if (timerCallbackCode.isError)
      throw CurlError.fromMCode(timerCallbackCode)

  def getWithCleanUp(
      multiHandle: Ptr[libcurl.CURLM],
      epoll: Epoll,
      maxConcurrentConnections: Int,
      maxConnections: Int,
  ): (CurlMultiScheduler, () => Unit) = {
    val scheduler =
      new CurlMultiScheduler(multiHandle, epoll, maxConcurrentConnections, maxConnections)

    // Setting up event based callbacks
    setUpCallbacks(scheduler, multiHandle)

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
