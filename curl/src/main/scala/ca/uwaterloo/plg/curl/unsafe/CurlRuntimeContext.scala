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

  /** Clean up the runtime background processes
    * and handles
    */
  def cleanUp(): Unit = ???
}
