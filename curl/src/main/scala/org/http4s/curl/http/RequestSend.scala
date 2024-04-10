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

package org.http4s.curl.http

import org.http4s.curl.internal.Utils
import org.http4s.curl.unsafe.libcurl_const

import scalanative.unsafe._
import scalanative.libc.string._
import scalanative.unsigned._
import scala.collection.mutable.ArrayBuffer

final private[curl] class RequestSend private (
    val content: Array[Byte],
    var offset: Int,
) {
  def onRead(
      buffer: Ptr[CChar],
      size: CSize,
      nitems: CSize,
  ): CSize =
    if offset >= content.length then return Size.intToSize(0).toUSize
    val contentPtr = content.at(offset)
    val copyAmount = Math.min(size.toInt * nitems.toInt, content.length - offset)
    val copyAmountUSize = Size.intToSize(copyAmount).toUSize
    memcpy(buffer, contentPtr, copyAmountUSize)
    offset += copyAmount
    copyAmountUSize
}

private[curl] object RequestSend {
  def apply(content: String): RequestSend =
    val bytes = content.getBytes()
    new RequestSend(bytes, 0)

  private[curl] def readCallback(
      buffer: Ptr[CChar],
      size: CSize,
      nitems: CSize,
      userdata: Ptr[Byte],
  ): CSize = Utils.fromPtr[RequestSend](userdata).onRead(buffer, size, nitems)
}
