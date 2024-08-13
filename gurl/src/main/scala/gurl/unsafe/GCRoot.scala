package gurl.unsafe

import scala.collection.mutable

class GCRoot {
  private val roots = mutable.HashSet.empty[Object]

  def add(obj: Object): Unit =
    synchronized:
      roots.add(obj)

  def remove(obj: Object): Boolean =
    synchronized:
      roots.remove(obj)

  def size: Int = synchronized(roots.size)
}