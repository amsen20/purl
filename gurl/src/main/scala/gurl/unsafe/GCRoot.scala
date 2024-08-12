package gurl.unsafe

import scala.collection.mutable

class GCRoot {
  private val roots = mutable.HashSet.empty[Object]
  private var len = 0

  def add(obj: Object): Unit =
    roots.add(obj)

  def remove(obj: Object): Boolean =
    roots.remove(obj)

  def size: Int = len
}
