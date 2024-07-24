package gurl
package multi

import gurl.unsafe.CurlRuntimeContext
import gurl.internal.Utils
import gurl.unsafe.libcurl._
import scala.scalanative.unsafe._

final private[gurl] class MultiSocket(cc: CurlRuntimeContext) {
  def socketCallback(
      easy: Ptr[CURL],
      sockFd: CInt,
      what: CInt,
  ): CInt =
    cc.expectSocket(easy, sockFd, what)
}

private[gurl] object MultiSocket {
  def apply(cc: CurlRuntimeContext): MultiSocket = new MultiSocket(cc)

  def socketCallback(
      easy: Ptr[CURL],
      sockFd: CInt,
      what: CInt,
      clientp: Ptr[Byte],
      socketp: Ptr[Byte],
  ): CInt =
    Utils
      .fromPtr[MultiSocket](clientp)
      .socketCallback(easy, sockFd, what)
}
