package gurl.unsafe

import scala.collection.mutable

class GCRoot {
  private val roots = mutable.ArrayBuffer.empty[Object]

  def addRoot(obj: Object): Unit =
    synchronized:
      roots.addOne(obj)

  def removeRoot(obj: Object): Unit =
    synchronized:
      val ind = roots.indexOf(obj)
      if ind >= 0 then roots.remove(ind)
}
