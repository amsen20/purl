package gurl
package multi

import gurl.unsafe.CurlRuntimeContext
import gurl.internal.Utils
import gurl.unsafe.libcurl._
import scala.scalanative.unsafe._

final private[gurl] class MultiTimer(cc: CurlRuntimeContext) {
  def timerCallback(
      timeout_ms: CLong
  ): CInt =
    cc.expectTimer(timeout_ms)
}

private[gurl] object MultiTimer {
  def apply(cc: CurlRuntimeContext): MultiTimer = new MultiTimer(cc)

  def timerCallback(
      multi: Ptr[CURLM],
      timeout_ms: CLong,
      clientp: Ptr[Byte],
  ): CInt =
    Utils
      .fromPtr[MultiTimer](clientp)
      .timerCallback(timeout_ms)
}
