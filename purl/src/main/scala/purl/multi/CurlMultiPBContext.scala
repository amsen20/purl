package purl
package multi

import purl.internal.GCRoot
import purl.internal.HandlePool
import purl.internal.Utils
import purl.unsafe._
import purl.unsafe.libcurl.CURL
import purl.CurlError
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
final private[purl] class CurlMultiPBContext(
    multiHandle: Ptr[libcurl.CURLM]
)(
    using poller: Poller
) extends CurlRuntimeContext {

  private val gcRoot = new GCRoot()

  // A map of all callbacks that are waiting for a response on a specific handle
  // NOTE Who ever that removes a handle should also call the callback with proper argument.
  private val callbacks = mutable.Map[Ptr[libcurl.CURL], Option[Throwable] => Unit]()

  // Pool of handles that are not used, this is for reusing the easy handles
  // FIXME handles can leak in following scenario:
  // - handle is got
  // - before assigning the handle to a callback some error happens
  // - The handle is not given back to the pool.
  private val handlePool = new HandlePool()

  // Set of current active file descriptors, that are being used by easy handles
  private val fdSet = mutable.HashSet[Int]()

  // The reason why the multi handle cannot continue working (is in corrupted state)
  private var reason = Option.empty[Throwable]

  // The deadline and has been set by libcurl timers
  private var deadline = -1
  // The ID for the deadline given by the poller
  private var deadLineID = -1L

  private def setReason(e: Throwable): Unit =
    synchronized:
      if reason.isEmpty then reason = Some(e)

  private def setReason(e: Option[Throwable]): Unit =
    e.foreach(setReason)

  private def throwIfNotSafe(): Unit =
    if !isSafe() then
      PBLogger.log("tried to do an operation, but the curl runtime is in corrupted state")
      throw reason.get

  private def isSafe(): Boolean =
    synchronized:
      reason.isEmpty

  override def addHandle(handle: Ptr[libcurl.CURL], cb: Option[Throwable] => Unit): Unit =
    // Fail fast if cannot continue.
    throwIfNotSafe()

    // The multi handle should be called from the poller thread.
    // Adding anything to it is done by registering a callback that does add that thing.
    poller.registerOnCycle(
      {
        // Always returns false, because it is one time usage callback.
        case Some(e: PollerCleanUpException) =>
          setReason(e)
          // Callback is not added, so call it
          // with the error and forget about it (user is informed).
          cb(Some(e))
          false
        case Some(e) =>
          setReason(e)
          cb(Some(e))
          false
        case None =>
          if !isSafe() then
            PBLogger.log("tried to add a handle, but the curl runtime is in corrupted state")
            cb(Some(reason.get))
            false
          else
            PBLogger.log("adding a handle to curl multi...")
            // TODO check if exists
            callbacks(handle) = cb
            val code = libcurl.curl_multi_add_handle(multiHandle, handle)
            if code.isError then
              setReason(CurlError.fromMCode(code))
              // Will be removed by the cleanUp.
              false
            else
              PBLogger.log("handle added")
              false
      },
      // If the callback cannot be executed, the runtime is corrupted.
      // All other possibilities are handled.
      setReason
    )

    poller.wakeUp()

  /**
   * Will remove the handle from the multi handle (if not already removed).
   * NOTE The method does not call the callback associated with given handle.
   */
  override def removeHandle(handle: Ptr[libcurl.CURL]): Unit =
    // Fail fast if cannot continue.
    throwIfNotSafe()

    // The multi handle should be called from the poller thread.
    // Adding anything to it is done by registering a callback that does add that thing.
    poller.registerOnCycle(
      {
        // Always returns false, because it is one time usage callback.
        case Some(e: PollerCleanUpException) =>
          setReason(e)
          // Will be (or was) removed in cleanUp.
          false
        case Some(e) =>
          setReason(e)
          // Will be (or was) removed in cleanUp.
          false
        case None =>
          if !isSafe() then
            PBLogger.log("tried to remove a handle, but the curl runtime is in corrupted state")
            // Will be (or was) removed in cleanUp.
            false
          else
            PBLogger.log("removing a handle from curl multi...")
            callbacks
              .remove(handle)
              .foreach(_ =>
                handlePool.giveBack(handle)
                val code = libcurl.curl_multi_remove_handle(multiHandle, handle)
                if code.isError then
                  setReason(CurlError.fromMCode(code))
                  // Will be (or was) removed in cleanUp.
                else PBLogger.log("handle removed")
              )
            false
      },
      // If the callback cannot be executed, the runtime is corrupted.
      // All other possibilities are handled.
      setReason
    )

    poller.wakeUp()

  override def getNewHandle(): Ptr[libcurl.CURL] =
    handlePool.get()

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
    if fdSet.contains(fd) then
      PBLogger.log(s"changed events on fd: ${fd}...")
      poller.expectFromFd(fd, events)
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
        fdSet.remove(socketFd)
        PBLogger.log("HHHHHHHHEERREEE")
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

  def start: Poller#OnStart =
    case Some(e: PollerCleanUpException) => false
    case Some(e) =>
      setReason(e)
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
          setReason(CurlError.fromMCode(actionCode))
          false
        else true

  def handleEventOnFd(fd: Int): Poller#OnFd =
    case Right(e: PollerCleanUpException) => false
    case Right(e) =>
      setReason(e)

      fdSet.remove(fd)
      false
    case Left(events) =>
      if !isSafe() then
        PBLogger.log(
          s"tried to handle an event for ${fd}, but the curl runtime is in corrupted state"
        )

        fdSet.remove(fd)
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
          setReason(CurlError.fromMCode(actionCode))

          fdSet.remove(fd)
          false
        else true

  def eachCycle: Poller#OnCycle =
    case Some(e: PollerCleanUpException) => false
    case Some(e) =>
      setReason(e)
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
                handlePool.giveBack(handle)
                val result = libcurl.curl_CURLMsg_data_result(info)
                // The callback should remove the handle
                cb(
                  if (result.isOk) None
                  else Some(CurlError.fromCode(result))
                )

                val code = libcurl.curl_multi_remove_handle(multiHandle, handle)
                if code.isError then setReason(CurlError.fromMCode(code))
              }
            }

            reason.isEmpty // continue processing messages
          }
        }) {}
        reason.isEmpty
      } else reason.isEmpty

  def onDeadline: Poller#OnDeadline =
    case Some(e: PollerCleanUpException) => false
    case Some(e) =>
      setReason(e)
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
          setReason(CurlError.fromMCode(actionCode))
          false
        else true

  /**
   * Cleans up all the easy handles
   */
  def cleanUp(): Unit =
    setReason(CurlMultiCleanUpError)

    // The cleanUp is called from the poller thread.
    // WARN this callback may takes a while to execute.
    poller.registerOnCycle(
      { case _ =>
        // Inform their callbacks that the runtime does not continue to work.
        // The callbacks may try to remove the handles themselves.
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

          handlePool.giveBack(handle)

        // cleaning up the handles
        PBLogger.log("cleaning up handles...")
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

        // It is a one time usage callback.
        false
      },
      {
        case Some(e: PollerCleanUpException) => ()
        case Some(e) => PBLogger.log("cleanUp couldn't be done completely due to: " + e)
        case None    => ()
      }
    )

}

private[purl] object CurlMultiPBContext {

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
    poller.registerOnStart(cmc.start, cmc.setReason)
    poller.registerOnCycle(cmc.eachCycle, cmc.setReason)

  def getCurlMultiContext(
      multiHandle: Ptr[libcurl.CURLM]
  )(
      using poller: Poller
  ): CurlMultiPBContext = {
    val cmc =
      new CurlMultiPBContext(multiHandle)

    // Setting up event based callbacks
    setUpCurlCallbacks(cmc, multiHandle)

    // Setting up poller based callbacks
    setUpPollerCallbacks(poller, cmc)

    cmc
  }

}
