package purl
package internal

import scala.scalanative.runtime
import scala.scalanative.runtime.Intrinsics
import scala.scalanative.unsafe._
import scala.scalanative.libc.string._
import scala.collection.mutable.ArrayBuffer
import pollerBear.logger.PBLogger

private[purl] object Utils {
  def toPtr(a: AnyRef): Ptr[Byte] =
    runtime.fromRawPtr(Intrinsics.castObjectToRawPtr(a))

  def fromPtr[A](ptr: Ptr[Byte]): A =
    Intrinsics.castRawPtrToObject(runtime.toRawPtr(ptr)).asInstanceOf[A]
}
