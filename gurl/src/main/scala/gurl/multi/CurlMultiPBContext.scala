package gurl
package multi

import gurl.internal.Utils
import gurl.unsafe._
import gurl.unsafe.libcurl.CURL
import gurl.CurlError
import pollerBear.epoll._
import pollerBear.logger.PBLogger
import pollerBear.runtime.Poller
import pollerBear.runtime.PollerCleanUpException
import scala.collection.mutable
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

/**
 * The multi curl interface that registers curl callbacks in poller bear.
 *
 * @param multiHandle
 * @param maxConcurrentConnections
 * @param maxConnections
 */
final private[gurl] class CurlMultiPBContext(
    multiHandle: Ptr[libcurl.CURLM],
    maxConcurrentConnections: Int,
    maxConnections: Int
)(
    using poller: Poller
) extends CurlRuntimeContext {

  private val gcRoot = new GCRoot()

  // A map of all callbacks that are waiting for a response on a specific handle
  private val callbacks = mutable.Map[Ptr[libcurl.CURL], Option[Throwable] => Unit]()

  // Pool of handles that are not used, this is for reusing the easy handles
  private val handlePool = mutable.Queue[Ptr[libcurl.CURL]]()

  // Set of current active file descriptors, that are being used by easy handles
  private val fdSet = mutable.HashSet[Int]()

  // The reason why the multi handle cannot continue working (is in corrupted state)
  private var reason = Option.empty[Throwable]

  // The deadline and has been set by libcurl timers
  private var deadline = -1
  // The ID for the deadline given by the poller
  private var deadLineID = -1L

  private def throwIfNotSafe(): Unit =
    if !isSafe() then
      PBLogger.log("tried to do an operation, but the runtime is in corrupted state")
      throw reason.get

  private def isSafe(): Boolean =
    reason.isEmpty

  override def addHandle(handle: Ptr[libcurl.CURL], cb: Option[Throwable] => Unit): Unit =
    throwIfNotSafe()
    PBLogger.log("adding a handle to curl multi...")

    // TODO check if already exists
    callbacks(handle) = cb
    val code = libcurl.curl_multi_add_handle(multiHandle, handle)
    if (code.isError) throw CurlError.fromMCode(code)

    PBLogger.log("handle added")

  /**
   * If the method is called and the callback is still in the map,
   * it means that it is called from outside the lib curl runtime.
   * So the callback will be removed.
   * Otherwise, it is the runtime responsibility to clean the handle and cleanUp.
   */
  override def removeHandle(handle: Ptr[libcurl.CURL]): Unit =
    // TODO think of a better solution to this
    // throwIfNotSafe()
    // TODO change it to a more specific exception
    PBLogger.log("removing a handle from runtime...")

    callbacks.remove(handle).foreach { _ =>
      val code = libcurl.curl_multi_remove_handle(multiHandle, handle)
      if code.isError then throw CurlError.fromMCode(code)

      handlePool.enqueue(handle)
    }
    // called from the callback so no need to call the callback
    // cb(Some(new RuntimeException("handle removed")))

    PBLogger.log("handle removed")

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

  /**
   * Keeps track of the object to prevent it from being garbage collected
   */
  override def keepTrack(obj: Object): Unit =
    gcRoot.add(obj)

  /**
   * Forgets about the object so that it can be garbage collected
   */
  override def forget(obj: Object): Unit =
    gcRoot.remove(obj)

  override def monitorProgress(
      dltotal: CLongLong,
      dlnow: CLongLong,
      ultotal: CLongLong,
      ulnow: CLongLong
  ): CInt =
    // TODO should be based on the handle
    0

  private def addOrChangeFd(fd: Int, events: EpollEvents): Unit =
    if poller.expectFromFd(fd, events) then PBLogger.log(s"changed events on fd: ${fd}...")
    else
      PBLogger.log(s"adding events on fd: ${fd}...")
      poller.registerOnFd(fd, handleEventOnFd(fd), events)
      fdSet += fd // TODO should also erase the fd when the callback erases that

  override def expectSocket(easy: Ptr[CURL], socketFd: CInt, what: CInt): CInt =
    PBLogger.log(s"expecting socket on fd: ${socketFd}...")

    what match {
      case libcurl_const.CURL_POLL_IN =>
        PBLogger.log("poll in")
        addOrChangeFd(socketFd, EpollInputEvents().input())
      case libcurl_const.CURL_POLL_OUT =>
        PBLogger.log("poll out")
        addOrChangeFd(socketFd, EpollInputEvents().output())
      case libcurl_const.CURL_POLL_INOUT =>
        PBLogger.log("poll inout")
        addOrChangeFd(socketFd, EpollInputEvents().input().output())
      case libcurl_const.CURL_POLL_REMOVE =>
        PBLogger.log("poll remove")
        fdSet -= socketFd
        poller.removeOnFd(socketFd) // TODO check if it works
      case _ =>
      // Handle other cases here
    }

    0 // success

  override def expectTimer(Ctimeout: CLong): CInt =
    PBLogger.log("expecting timer...")
    val timeout = Ctimeout.toLong
    val now     = System.currentTimeMillis()
    deadline = if timeout == -1 then -1 else (now + timeout).toInt
    if deadline != -1 then
      if deadLineID != -1 then
        PBLogger.log("removing the last deadline...")
        poller.removeOnDeadline(deadLineID)
      PBLogger.log("adding new deadline...")
      deadLineID = poller.registerOnDeadline(deadline, onDeadline)

    0 // success

  def start: Poller#onStart =
    case Some(e: PollerCleanUpException) => false
    case Some(e) =>
      if reason.isEmpty then reason = Some(e)
      false
    case None =>
      if !isSafe() then
        PBLogger.log("tried to start the curl multi, but the curl runtime is in corrupted state")
        false
      else
        PBLogger.log("starting the curl multi...")
        val runningHandles = stackalloc[CInt]()
        val actionCode = libcurl.curl_multi_socket_action(
          multiHandle,
          libcurl_const.CURL_SOCKET_TIMEOUT,
          0,
          runningHandles
        )
        if (actionCode.isError)
          reason = Some(CurlError.fromMCode(actionCode))
          false
        else true

  def handleEventOnFd(fd: Int): Poller#onFd =
    case Right(e: PollerCleanUpException) => false
    case Right(e) =>
      if reason.isEmpty then reason = Some(e)
      false
    case Left(events) =>
      if !isSafe() then
        PBLogger.log(
          s"tried to handle an event for ${fd}, but the curl runtime is in corrupted state"
        )
        false
      else
        PBLogger.log(s"handling an event on fd: ${fd}...")
        val runningHandles = stackalloc[CInt]()
        var what           = 0
        if events.isInput then what |= libcurl_const.CURL_CSELECT_IN
        if events.isOutput then what |= libcurl_const.CURL_CSELECT_OUT
        if events.isError then what |= libcurl_const.CURL_CSELECT_ERR

        val actionCode =
          libcurl.curl_multi_socket_action(multiHandle, fd, what, runningHandles)
        if (actionCode.isError)
          reason = Some(CurlError.fromMCode(actionCode))
          false
        else true

  def eachCycle: Poller#onCycle =
    case Some(e: PollerCleanUpException) => false
    case Some(e) =>
      if reason.isEmpty then reason = Some(e)
      false
    case None =>
      if !isSafe() then
        PBLogger.log("tried to do a cycle, but the curl runtime is in corrupted state")
        false
      else if (!callbacks.isEmpty) {
        while ({
          PBLogger.log("looking for messages...")
          val msgsInQueue = stackalloc[CInt]()
          val info        = libcurl.curl_multi_info_read(multiHandle, msgsInQueue)

          if (info == null) false // no more messages
          else {
            PBLogger.log("a new message!")
            val curMsg = libcurl.curl_CURLMsg_msg(info)

            if (curMsg == libcurl_const.CURLMSG_DONE) {
              PBLogger.log("a request is done!")
              val handle = libcurl.curl_CURLMsg_easy_handle(info)

              callbacks.remove(handle).foreach { cb =>
                val result = libcurl.curl_CURLMsg_data_result(info)
                // The callback should remove the handle
                cb(
                  if (result.isOk) None
                  else Some(CurlError.fromCode(result))
                )

                val code = libcurl.curl_multi_remove_handle(multiHandle, handle)
                if code.isError then reason = Some(CurlError.fromMCode(code))
                else handlePool.enqueue(handle)
              }
            }

            reason.isEmpty // continue processing messages
          }
        }) {}
        reason.isEmpty
      } else reason.isEmpty

  def onDeadline: Poller#onDeadline =
    case Some(e: PollerCleanUpException) => false
    case Some(e) =>
      if reason.isEmpty then reason = Some(e)
      false
    case None =>
      if !isSafe() then
        PBLogger.log("tried to handle a deadline, but the curl runtime is in corrupted state")
        false
      else
        deadline = -1
        deadLineID = -1
        val runningHandles = stackalloc[CInt]()
        val actionCode = libcurl.curl_multi_socket_action(
          multiHandle,
          libcurl_const.CURL_SOCKET_TIMEOUT,
          0,
          runningHandles
        )
        if (actionCode.isError)
          reason = Some(CurlError.fromMCode(actionCode))
          false
        else true

  /**
   * Cleans up all the easy handles
   */
  def cleanUp(): Unit =
    // TODO use a more specific exception
    if reason.isEmpty then reason = Some(new RuntimeException("cleaning up the curl runtime"))

    // Inform their callbacks that the
    // polling is stopped.
    // The callbacks should remove the handles their selves.
    PBLogger.log("informing callbacks...")

    // Copying the callbacks to clear the callbacks.
    val cbAndHandles = for (handle, cb) <- callbacks yield (handle, cb)

    // The callbacks are cleared so that `removeHandle` will see
    // that the callback is not present and then not cleanup.
    callbacks.clear()
    for (handle, cb) <- cbAndHandles do
      cb(reason)

      val code = libcurl.curl_multi_remove_handle(multiHandle, handle)
      if code.isError then throw CurlError.fromMCode(code)

      handlePool.enqueue(handle)

    // Cleaning up the handles
    PBLogger.log("cleaning up handles...")
    handlePool.foreach { case handle =>
      libcurl.curl_easy_cleanup(handle)
    }

    handlePool.clear()

    // clearing fdset from the poller
    PBLogger.log("cleaning up fds...")
    fdSet.foreach { case fd =>
      // Maybe the poller itself is not on a safe state
      // so trying to remove will cause an exception.
      // I(amsen) believe this should NOT happen.
      try poller.removeOnFd(fd)
      catch { case _ => () }
    }

}

private[gurl] object CurlMultiPBContext {

  def setUpCurlCallbacks(cmc: CurlMultiPBContext, multiHandle: Ptr[libcurl.CURLM]): Unit =
    val multiSocket = MultiSocket(cmc.asInstanceOf[CurlRuntimeContext])
    cmc.keepTrack(multiSocket)

    // Socket data:
    val socketDataCode = libcurl.curl_multi_setopt_socket_data(
      multiHandle,
      libcurl_const.CURLMOPT_SOCKETDATA,
      Utils.toPtr(multiSocket)
    )
    if (socketDataCode.isError)
      throw CurlError.fromMCode(socketDataCode)

    // Socket callback:
    val socketCallbackCode = libcurl.curl_multi_setopt_socket_function(
      multiHandle,
      libcurl_const.CURLMOPT_SOCKETFUNCTION,
      MultiSocket.socketCallback(_, _, _, _, _)
    )
    if (socketCallbackCode.isError)
      throw CurlError.fromMCode(socketCallbackCode)

    val multiTimer = MultiTimer(cmc.asInstanceOf[CurlRuntimeContext])
    cmc.keepTrack(multiTimer)

    // Timer data:
    val timerDataCode = libcurl.curl_multi_setopt_timer_data(
      multiHandle,
      libcurl_const.CURLMOPT_TIMERDATA,
      Utils.toPtr(multiTimer)
    )

    // Timer callback:
    val timerCallbackCode = libcurl.curl_multi_setopt_timer_function(
      multiHandle,
      libcurl_const.CURLMOPT_TIMERFUNCTION,
      MultiTimer.timerCallback(_, _, _)
    )
    if (timerCallbackCode.isError)
      throw CurlError.fromMCode(timerCallbackCode)

  def setUpPollerCallbacks(poller: Poller, cmc: CurlMultiPBContext): Unit =
    poller.registerOnStart(cmc.start)
    poller.registerOnCycle(cmc.eachCycle)

  def getCurlMultiContext(
      multiHandle: Ptr[libcurl.CURLM],
      maxConcurrentConnections: Int,
      maxConnections: Int
  )(
      using poller: Poller
  ): CurlMultiPBContext = {
    val cmc =
      new CurlMultiPBContext(multiHandle, maxConcurrentConnections, maxConnections)

    // Setting up event based callbacks
    setUpCurlCallbacks(cmc, multiHandle)

    // Setting up poller based callbacks
    setUpPollerCallbacks(poller, cmc)

    cmc
  }

}
