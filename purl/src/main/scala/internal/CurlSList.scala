package purl
package internal

import collection.mutable
import purl.internal.Utils.toPtr
import purl.unsafe.libcurl._
import scala.scalanative.runtime.RawPtr
import scala.scalanative.unsafe._
import scala.util.Failure
import scala.util.Success

final private[purl] class CurlSList(private[purl] var list: Ptr[curl_slist])(
    using Zone
) {

  @inline def append(str: String): Unit =
    list = curl_slist_append(list, toCString(str))

  @inline def toPtr = list
}

/**
 * Create a new curl_slist with each string
 * in the given zone and runs the body with
 * the curl_slist.
 */
private[purl] object CurlSList {

  def getSList(): (CurlSList, () => Unit) =
    val zone = Zone.open()
    val slist: CurlSList = CurlSList(list = null)(
      using zone
    )

    (
      slist,
      () =>
        if slist != null && slist.list != null then curl_slist_free_all(slist.list)
        zone.close()
    )

}
