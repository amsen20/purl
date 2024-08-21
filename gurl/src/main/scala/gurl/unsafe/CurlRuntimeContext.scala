package gurl
package unsafe

import scala.scalanative.unsafe._
import scala.scalanative.unsafe.Ptr

abstract class CurlRuntimeContext {

  /**
   * Add a handle to the runtime context.
   *
   * @param handle: An unsafe pointer to a CURL handle
   * @param cb: A callback to be called when the process is either finished or failed
   */
  def addHandle(handle: Ptr[libcurl.CURL], cb: Option[Throwable] => Unit): Unit = ???

  /**
   * Remove a handle from the runtime context.
   * There is a possibility that the handle is not added at all.
   *
   * @param handle An unsafe pointer to a CURL handle
   * @param removeCallback Whether to remove the callback associated with the handle
   */
  def removeHandle(handle: Ptr[libcurl.CURL]): Unit = ???

  /**
   * Get a new handle from the runtime context.
   *
   * @return An unsafe pointer to a CURL handle
   */
  def getNewHandle(): Ptr[libcurl.CURL] = ???

  /**
   * Make it possible to keep track of some objects
   * so that they are reachable as long as they are reachable
   * from the runtime context.
   *
   * @param obj
   */
  def keepTrack(obj: Object): Unit = ???

  /**
   * Forget about an object.
   * This is useful when the object is no longer needed.
   *
   * @param obj
   */
  def forget(obj: Object): Unit = ???

  /**
   * Monitors the progress of a connection.
   * For now this just abort when the curl runtime is shutting down.
   *
   * @return 0 if the connection is still in progress
   */
  def monitorProgress(
      dltotal: CLongLong,
      dlnow: CLongLong,
      ultotal: CLongLong,
      ulnow: CLongLong
  ): CInt = ???

  /**
   * Informs the runtime to expect an event
   * for a specific socket.
   *
   * @return 0 always
   */
  def expectSocket(
      easy: Ptr[libcurl.CURL],
      socketFd: CInt,
      what: CInt
  ): CInt = ???

  /**
   * Informs the runtime to expect a timer event
   *
   * @return 0 always
   */
  def expectTimer(
      timeout_ms: CLong
  ): CInt = ???

}
