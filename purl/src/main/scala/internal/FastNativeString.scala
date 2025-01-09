package purl
package internal

import scala.scalanative.libc.stdlib
import scala.scalanative.libc.string
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

/**
 * A fast implementation for native string.
 * This string is using underling C string (char*) to store the data.
 * The string's elements are immutable, but the string itself is mutable.
 *
 * NOTE:
 * The string is not garbage collected, and you should call `free` method to free the memory.
 */
final class FastNativeString(val startSize: Int) {
  // The size of the underlying C string.
  // cap > size + 1
  private var cap = Math.max(startSize, 0) + 1
  private var str = stdlib.malloc(cap)

  // The number of characters in the string (the null terminator is not counted).
  private var size = 0

  private def nullTerminate(): Unit =
    str.update(size, 0.toByte)

  nullTerminate()

  private def updateSize(newSize: Int): Unit =
    size = newSize
    nullTerminate()

  def length: Int = size

  def append(other: CString, otherSize: Int): Unit =
    val newSize = size + otherSize
    if (newSize >= cap) {
      var newCap = cap
      while newCap <= newSize do newCap *= 2

      val newStr = stdlib.malloc(newCap)
      string.strncpy(newStr, str, size.toCSize)
      string.strncpy(newStr + size, other, otherSize.toCSize)
      stdlib.free(str)
      str = newStr
      cap = newCap
    } else {
      string.strncpy(str + size, other, otherSize.toCSize)
    }

    updateSize(newSize)

  /**
   * @param other should be null-terminated. Otherwise, use `append(str, len)`
   */
  def append(other: CString): Unit =
    append(other, string.strlen(other).toInt)

  def append(other: FastNativeString): Unit =
    append(other.str)

  def append(other: String): Unit =
    Zone:
      append(toCString(other))

  def apply(index: Int): Char =
    if (index < 0 || index >= size) {
      throw new IndexOutOfBoundsException(s"index: $index, size: $size")
    }
    str(index).toChar

  def substring(fromIndex: Int, toIndex: Int): FastNativeString =
    if (fromIndex < 0 || fromIndex >= size) {
      throw new IndexOutOfBoundsException(s"fromIndex: $fromIndex, size: $size")
    }
    if (toIndex < 0 || toIndex > size) {
      throw new IndexOutOfBoundsException(s"toIndex: $toIndex, size: $size")
    }
    if (fromIndex >= toIndex) {
      throw new IllegalArgumentException(s"fromIndex: $fromIndex, toIndex: $toIndex")
    }

    val newStr = new FastNativeString(toIndex - fromIndex)
    string.strncpy(newStr.str, str + fromIndex, (toIndex - fromIndex).toCSize)
    newStr.updateSize(toIndex - fromIndex)

    newStr

  def indexOf(ch: Char, fromIndex: Int): Int =
    val cch    = ch.toByte
    val result = string.strchr(str + fromIndex, cch)
    if (result == null) {
      -1
    } else {
      (result - str).toInt
    }

  def indexOf(other: CString, fromIndex: Int): Int =
    val result = string.strstr(str + fromIndex, other)
    if (result == null) {
      -1
    } else {
      (result - str).toInt
    }

  def indexOf(other: FastNativeString, fromIndex: Int): Int =
    indexOf(other.str, fromIndex)

  def indexOf(other: String, fromIndex: Int): Int =
    Zone:
      indexOf(toCString(other), fromIndex)

  override def toString(): String = 
    fromCString(str)

  def free(): Unit =
    stdlib.free(str)
    cap = 0
    size = 0

}

object FastNativeString {

  def apply(startCap: Int = 1): FastNativeString =
    new FastNativeString(startCap)

  def apply(str: String): FastNativeString =
    val fastStr = new FastNativeString(str.length)
    fastStr.append(str)
    fastStr

}
