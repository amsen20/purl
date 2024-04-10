package org.http4s.curl
package unsafe

import scala.scalanative.unsafe.Ptr

abstract class CurlRuntimeContext {
  def addHandle(handle: Ptr[libcurl.CURL], cb: Either[Throwable, Unit] => Unit): Unit = ???
  def cleanUp(): Unit = ???
}
