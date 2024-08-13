package gurl.unsafe

import scala.collection.mutable

class GCRoot {
  private val roots = mutable.HashSet.empty[Object]

  def add(obj: Object): Unit =
    roots.add(obj)

  def remove(obj: Object): Boolean =
    roots.remove(obj)

  def size: Int = roots.size
}
