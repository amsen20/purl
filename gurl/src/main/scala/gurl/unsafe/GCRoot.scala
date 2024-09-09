package gurl.unsafe

import scala.collection.mutable

class GCRoot {
  private val roots = mutable.HashSet.empty[Object]

  def addRoot(obj: Object): Unit =
    synchronized:
      roots.add(obj)

  def removeRoot(obj: Object): Unit =
    synchronized:
      roots.remove(obj)
}
