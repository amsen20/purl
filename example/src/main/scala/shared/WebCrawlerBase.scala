package shared

import scala.concurrent.ExecutionContext
import scala.collection.mutable
import scala.io.Source
import scala.util._
import scala.math.min
import scala.concurrent.duration

import gears.async.*
import gears.async.default.given
import scala.collection.mutable.ListBuffer
import scala.compiletime.ops.double
import gears.async.Future.MutableCollector

class CollectorWithSize[T] extends MutableCollector[T]():
  var size = 0
  inline def addOne(future: Future[T]): Unit =
    addFuture(future)
    size += 1

  def next()(using Async) =
    assert(size > 0)
    size -= 1
    results.read().right.get

abstract class WebCrawlerBase {
  val found = mutable.Set[String]()
  val successfulExplored = mutable.Set[String]()

  // Only for analysis
  var charsDownloaded = 0

  def getWebContent(url: String)(using Async): Option[String] = ???

  def exploreLayer(
      seen: Set[String],
      layer: Set[String],
      maxConnections: Int,
  )(using Async): Set[String] = Async.group {
    val nextLayer: mutable.Set[String] = mutable.Set()
    val layerIt = layer.toIterator
    var currentConnections = 0
    val resultFutures = CollectorWithSize[(Option[String], String)]()

    def goNext(): Unit =
      if !layerIt.hasNext then return
      val url = layerIt.next()
      resultFutures.addOne(
        Future:
          val ret = getWebContent(url)
          (ret, url)
      )

    for _ <- 0 until min(maxConnections, layer.size) do goNext()

    while resultFutures.size > 0 do
      val res = resultFutures.next().awaitResult
      res match
        case Success(Some(content), url) =>
          successfulExplored.add(url)
          charsDownloaded += content.length

          val links = UrlUtils.extractLinks(url, content)
          found ++= links
          nextLayer ++= links
          goNext()
        case Success(None, _) => ()
        case Failure(e) =>
          println(e)
          e.printStackTrace()
    end while

    nextLayer.filter(url => !seen.contains(url) && !layer.contains(url)).toSet
  }

  def crawl(url: String, maxConnections: Int)(using Async): Unit = {
    found += url
    val seen = mutable.Set[String]()
    var layer = Set[String](url)

    /*
      TODO Make the function return a lazy list
      which returns found links one by one,
      then the maxDepth can be removed.
     */
    val maxDepth = 1000 // if DEBUG then 2 else 1000

    for depth <- 0 until maxDepth do
      val nextLayer = exploreLayer(seen.toSet, layer, maxConnections)
      seen ++= layer
      layer = nextLayer
  }

}
