package gurl.http

import gurl.unsafe.CurlRuntimeContext
import gurl.internal.Utils
import scala.scalanative.unsafe._

final private[gurl] class RequestProgress(using cc: CurlRuntimeContext) {
  def progressCallback(
      dltotal: CLongLong,
      dlnow: CLongLong,
      ultotal: CLongLong,
      ulnow: CLongLong,
  ): CInt =
    cc.monitorProgress(dltotal, dlnow, ultotal, ulnow)
}

private[gurl] object RequestProgress {
  def apply(using cc: CurlRuntimeContext)(): RequestProgress = new RequestProgress()

  def progressCallback(
      clientp: Ptr[Byte],
      dltotal: CLongLong,
      dlnow: CLongLong,
      ultotal: CLongLong,
      ulnow: CLongLong,
  ): CInt =
    Utils
      .fromPtr[RequestProgress](clientp)
      .progressCallback(dltotal, dlnow, ultotal, ulnow)
}
