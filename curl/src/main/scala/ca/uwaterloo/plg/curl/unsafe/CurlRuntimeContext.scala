package ca.uwaterloo.plg.curl
package unsafe

import scala.scalanative.unsafe.Ptr

abstract class CurlRuntimeContext {

  /** Add a handle to the runtime context
    *
    * @param handle: An unsafe pointer to a CURL handle
    * @param cb: A callback to be called when the process is either finished or failed
    */
  def addHandle(handle: Ptr[libcurl.CURL], cb: Either[Throwable, Unit] => Unit): Unit = ???

  /** Make it possible to keep track of some objects
    * so that they are reachable as long as they are reachable
    * from the runtime context.
    *
    * @param obj
    */
  def keepTrack(obj: Object): Unit = ???
}
