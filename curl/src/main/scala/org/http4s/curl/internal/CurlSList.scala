/*
 * Copyright 2022 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
    println("appppppppeeennnddd called")
    list = curl_slist_append(list, toCString(str))
  @inline val toPtr = list

  override def toString(): String = "hi"
}

private[curl] object CurlSList {
  def withSList[T](body: CurlSList => T)(using zone: Zone): T =
    var slist: CurlSList = null
    try {
      slist = CurlSList(list = null)
      slist.append("Content-Type: application/json")
      println("the pointer is: " + slist.list.toString())
      println("the slist pointer before body" + toPtr(slist).toString())
      val r = body(slist)
      println("the slist pointer after body" + toPtr(slist).toString())
      println("theeeeeeeeee pointer is: " + slist.list.toString())
      r
    } finally {
      println("freeing the the slist")
      println("some thing else")
      println("the object is: " + slist.toString())
      println("the pointer is null: " + (slist.list == null))
      println("the pointer is: " + slist.list.toString())
      if slist != null && slist.list != null then curl_slist_free_all(slist.list)
      // println("done freeing the slist")
    }
}
