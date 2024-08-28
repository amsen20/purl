package purl
package multi

import purl.unsafe.CurlRuntimeContext
import purl.internal.Utils
import purl.unsafe.libcurl._
import scala.scalanative.unsafe._

final private[purl] class MultiSocket(cc: CurlRuntimeContext) {
  def socketCallback(
      easy: Ptr[CURL],
      sockFd: CInt,
      what: CInt,
  ): CInt =
    cc.expectSocket(easy, sockFd, what)
}

private[purl] object MultiSocket {
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
