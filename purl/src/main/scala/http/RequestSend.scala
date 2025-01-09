package purl
package http

import purl.internal.Utils
import purl.unsafe.libcurl_const
import scala.collection.mutable.ArrayBuffer
import scalanative.libc.string._
import scalanative.unsafe._
import scalanative.unsigned._

final private[purl] class RequestSend private (
    val content: CString,
    var offset: Int
) {

  def onRead(
      buffer: Ptr[CChar],
      size: CSize,
      nitems: CSize
  ): CSize =
    val contentLength = strlen(content).toInt
    if offset >= contentLength then return Size.intToSize(0).toUSize
    val copyAmount      = Math.min(size.toInt * nitems.toInt, contentLength - offset)
    val copyAmountUSize = Size.intToSize(copyAmount).toUSize
    memcpy(buffer, content, copyAmountUSize)
    offset += copyAmount
    copyAmountUSize

}

private[purl] object RequestSend {

  def apply(content: CString): RequestSend =
    new RequestSend(content, 0)

  private[purl] def readCallback(
      buffer: Ptr[CChar],
      size: CSize,
      nitems: CSize,
      userdata: Ptr[Byte]
  ): CSize = Utils.fromPtr[RequestSend](userdata).onRead(buffer, size, nitems)

}
