package org.http4s.curl.internal

import org.http4s.curl.unsafe.libcurl._

import scala.scalanative.unsafe._
import scala.util.Failure
import scala.util.Success
import scala.scalanative.runtime.RawPtr
import org.http4s.curl.internal.Utils.toPtr

final private[curl] class CurlSList(private[curl] var list: Ptr[curl_slist])(using
    zone: Zone
) {
  @inline def append(str: String): Unit =
    list = curl_slist_append(list, toCString(str))
  @inline def toPtr = list
}

/** Create a new curl_slist with each string
  * in the given zone and runs the body with
  * the curl_slist.
  */
private[curl] object CurlSList {
  def withSList[T](body: CurlSList => T)(using zone: Zone): T =
    val slist: CurlSList = CurlSList(list = null)
    try {
      // FIXME erase me after testing
      slist.append("Content-Type: application/json")
      body(slist)
    } finally if slist != null && slist.list != null then curl_slist_free_all(slist.list)
}
