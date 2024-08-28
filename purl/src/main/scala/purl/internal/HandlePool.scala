package purl
package internal

import purl.unsafe._
import scala.collection.mutable
import scala.scalanative.unsafe._

/**
 * A thread-safe libcurl pool of handles.
 */
class HandlePool {
  private val handlesSet   = mutable.HashSet.empty[Ptr[libcurl.CURL]]
  private val handlesQueue = mutable.Queue.empty[Ptr[libcurl.CURL]]

  /**
   * Get a fresh handle.
   */
  def get(): Ptr[libcurl.CURL] = synchronized {
    if (handlesQueue.isEmpty) {
      val handle = libcurl.curl_easy_init()
      if (handle == null)
        throw new RuntimeException("curl_easy_init")
      handle
    } else {
      val handle = handlesQueue.dequeue()
      handlesSet.remove(handle)
      libcurl.curl_easy_reset(handle)
      handle
    }
  }

  /**
   * You can give back a handle to the pool multiple times.
   * But you must not use it after giving it back (until getting it back from `get` method).
   */
  def giveBack(handle: Ptr[libcurl.CURL]): Unit = synchronized {
    if handlesSet.add(handle) then handlesQueue.enqueue(handle)
  }

  def clear(): Unit = synchronized {
    handlesQueue.clear()
    handlesSet.foreach(libcurl.curl_easy_cleanup)
    handlesSet.clear()
  }

}
