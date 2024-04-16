package org.http4s.curl.internal

import org.http4s.curl.unsafe.libcurl._

import scala.scalanative.unsafe._
import scala.util.Failure
import scala.util.Success
import scala.scalanative.runtime.RawPtr
import org.http4s.curl.internal.Utils.toPtr
import collection.mutable

final private[curl] class CurlSList(private[curl] var list: Ptr[curl_slist]) {
  private val string_list_shadow: mutable.ArrayBuffer[Array[Byte]] = mutable.ArrayBuffer.empty

  @inline def append(str: Array[Byte]): Unit =
    // Store a copy of each for the lifetime of the CurlSList,
    // This results in not being cleaned up by the GC.
    string_list_shadow.addOne(str)
    list = curl_slist_append(list, str.at(0))
  @inline def toPtr = list
}

/** Create a new curl_slist with each string
  * in the given zone and runs the body with
  * the curl_slist.
  */
private[curl] object CurlSList {
  def withSList[T](body: CurlSList => T): T =
    val slist: CurlSList = CurlSList(list = null)
    try body(slist)
    finally if slist != null && slist.list != null then curl_slist_free_all(slist.list)
}
