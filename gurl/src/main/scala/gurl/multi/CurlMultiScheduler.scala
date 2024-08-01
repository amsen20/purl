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
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._
import scala.scalanative.posix.signal
import epoll.Epoll
import gurl.unsafe.libcurl.CURL
import scala.annotation.switch

class MultiRuntimeTimeOut extends RuntimeException("")

/** The multi curl scheduler that polls until it makes a new progress.
  * The scheduler is located in the same thread.
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

  // A map of all callbacks that are waiting for a response on a specific handle
  private val callbacks = mutable.Map[Ptr[libcurl.CURL], Option[Throwable] => Unit]()

  // Pool of handles that are not used, this is for reusing the easy handles
  private val handlePool = mutable.Queue[Ptr[libcurl.CURL]]()

  // A map of expected type of events on a specific file descriptor
  private val expectFromFd = mutable.Map[Int, Long]()

  // The reason why the scheduler cannot continue working (is in corrupted state)
  private var reason = Option.empty[Throwable]

  // The deadline and has been set by libcurl timers
  private var deadline = -1

  /** Checks if the runtime is in a corrupted state
    * and throws an exception representing the reason if it is.
    */
  private def checkIsSafe(): Unit =
    if reason.isDefined then
      GLogger.log("tried to do an operation, but the runtime is in corrupted state")
      throw reason.get

  /** Waits until any request has a response or the timeout is reached.
    */
  override def waitUntil(timeout: Long = -1): Unit =
    checkIsSafe()

    GLogger.log(s"waiting Until ${timeout}...")
    start()

    try poll(timeout)
    catch {
      case e: MultiRuntimeTimeOut =>
        throw e
      case e =>
        GLogger.log("exception caught %s".format(e.getMessage()))
        reason = Some(e)
        throw e
    }

    GLogger.log("done waiting...")

  /** Polls the events and processes them.
    *
    * @param internalTimeout
    */
  private def poll(internalTimeout: Long): Unit = {

    val timeout =
      val now = System.currentTimeMillis()
      val diff = math.max(deadline - now, 0)
      if deadline == -1 then internalTimeout
      else if internalTimeout == -1 then diff
      else math.min(diff, internalTimeout)

    val runningHandles = stackalloc[CInt]()

    if (!callbacks.isEmpty || timeout != -1) {
      GLogger.log("entering epolling...")
      val (events, didTimeout) = epoll.waitForEvents(timeout.toInt)

      if (didTimeout) {
        GLogger.log("timeout happened")
        val now = System.currentTimeMillis()
        if now < deadline then
          GLogger.log("the timeout was internal")
          // TODO should check timeout through other operations as well
          throw new MultiRuntimeTimeOut()
        else
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

          val actionCode =
            libcurl.curl_multi_socket_action(multiHandle, waitEvent.fd, what, runningHandles)
          if (actionCode.isError)
            throw CurlError.fromMCode(actionCode)
        }
      }

      GLogger.log("waken up from polling...")

      if (!callbacks.isEmpty) {

        while ({
          GLogger.log("looking for messages...")
          val msgsInQueue = stackalloc[CInt]()
          val info = libcurl.curl_multi_info_read(multiHandle, msgsInQueue)

          if (info == null) false // no more messages
          else {
            GLogger.log("a new message!")
            val curMsg = libcurl.curl_CURLMsg_msg(info)

            if (curMsg == libcurl_const.CURLMSG_DONE) {
              GLogger.log("a request is done!")
              val handle = libcurl.curl_CURLMsg_easy_handle(info)

              callbacks.remove(handle).foreach { cb =>
                val result = libcurl.curl_CURLMsg_data_result(info)
                cb(
                  if (result.isOk) None
                  else Some(CurlError.fromCode(result))
                )

                val code = libcurl.curl_multi_remove_handle(multiHandle, handle)
                if (code.isError)
                  throw CurlError.fromMCode(code)
                GLogger.log("removed the handle from the multi handle")
                handlePool.enqueue(handle)
              }
            }

            true // continue processing messages
          }
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

  override def addHandle(handle: Ptr[libcurl.CURL], cb: Option[Throwable] => Unit): Unit =
    checkIsSafe()
    GLogger.log("adding a handle to runtime...")

    // TODO check if already exists
    val code = libcurl.curl_multi_add_handle(multiHandle, handle)
    if (code.isError) throw CurlError.fromMCode(code)
    callbacks(handle) = cb

    GLogger.log("handle added")

  override def removeHandle(handle: Ptr[libcurl.CURL]): Unit =
    checkIsSafe()
    GLogger.log("removing a handle from runtime...")

    callbacks.remove(handle).foreach { cb =>
      cb(Some(new RuntimeException("handle removed")))

      val code = libcurl.curl_multi_remove_handle(multiHandle, handle)
      if code.isError then throw CurlError.fromMCode(code)

      handlePool.enqueue(handle)

      GLogger.log("handle removed")
    }

  override def getNewHandle(): Ptr[libcurl.CURL] =
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
    gcRoot.addRoot(obj)

  override def monitorProgress(
      dltotal: CLongLong,
      dlnow: CLongLong,
      ultotal: CLongLong,
      ulnow: CLongLong,
  ): CInt =
    // TODO should be based on the handle
    0

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
    deadline = if timeout == -1 then -1 else (now + timeout).toInt

    0 // success

  /** Cleans up all the easy handles
    */
  def cleanUp(): Unit =
    if reason.isEmpty then reason = Some(new RuntimeException("cleaning up the scheduler"))

    // Inform their callbacks that the
    // polling is stopped.
    GLogger.log("informing callbacks...")
    callbacks.foreach(_._2(reason))

    // Remove the handles from multi handle for
    // terminating all connections and cleaning multi handle
    // peacefully.
    GLogger.log("cleaning up handles...")
    (callbacks.map(_._1) ++ handlePool).foreach { case handle =>
      val code = libcurl.curl_multi_remove_handle(multiHandle, handle)
      if (code.isError)
        throw CurlError.fromMCode(code)
      libcurl.curl_easy_cleanup(handle)
    }

    callbacks.clear()
    handlePool.clear()
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

  def getScheduler(
      multiHandle: Ptr[libcurl.CURLM],
      epoll: Epoll,
      maxConcurrentConnections: Int,
      maxConnections: Int,
  ): CurlMultiScheduler = {
    val scheduler =
      new CurlMultiScheduler(multiHandle, epoll, maxConcurrentConnections, maxConnections)

    // Setting up event based callbacks
    setUpCallbacks(scheduler, multiHandle)

    scheduler
  }
}
