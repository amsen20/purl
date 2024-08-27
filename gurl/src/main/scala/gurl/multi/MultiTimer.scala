package purl
package multi

import purl.unsafe.CurlRuntimeContext
import purl.internal.Utils
import purl.unsafe.libcurl._
import scala.scalanative.unsafe._

final private[purl] class MultiTimer(cc: CurlRuntimeContext) {
  def timerCallback(
      timeout_ms: CLong
  ): CInt =
    cc.expectTimer(timeout_ms)
}

private[purl] object MultiTimer {
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
