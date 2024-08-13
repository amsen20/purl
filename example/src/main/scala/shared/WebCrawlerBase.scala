package shared

import scala.concurrent.ExecutionContext
import scala.io.Source
import scala.util._
import scala.math.min
import scala.concurrent.duration

import gears.async.*
import gears.async.default.given
import scala.compiletime.ops.double
import gears.async.Future.MutableCollector
import scala.collection.immutable.HashSet
import scala.annotation.tailrec

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
  var found = HashSet[String]()
  var successfulExplored = HashSet[String]()

  // Only for analysis
  var charsDownloaded = 0

  def getWebContent(url: String)(using Async): Option[String] = ???

  def exploreLayer(
      seen: HashSet[String],
      layer: Array[String],
      maxConnections: Int,
  )(using Async): Array[String] = Async.group {
    var nextLayer: HashSet[String] = HashSet()
    var layerInd = 0
    var currentConnections = 0
    val resultFutures = CollectorWithSize[(Option[String], String)]()

    def goNext(): Unit =
      val url = layer(layerInd)
      layerInd += 1
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
          successfulExplored = successfulExplored + url
          charsDownloaded += content.length

          val links = UrlUtils.extractLinks(url, content)
          found = found ++ links
          nextLayer = nextLayer ++ links

          if layerInd < layer.size then goNext()
        case Success(None, _) => ()
        case Failure(e) =>
          println(e)
          e.printStackTrace()
    end while

    (nextLayer -- seen).toArray
  }

  @tailrec
  final def crawlRecursive(
      seen: HashSet[String],
      layer: Array[String],
      maxConnections: Int,
      depth: Int,
  )(using Async): Unit =
    if depth != 0 then
      val nextLayer = exploreLayer(seen, layer, maxConnections)
      crawlRecursive(
        seen ++ nextLayer,
        nextLayer,
        maxConnections,
        depth - 1,
      )

  def crawl(url: String, maxConnections: Int)(using Async): Unit = {
    /*
      TODO Make the function return a lazy list
      which returns found links one by one,
      then the maxDepth can be removed.
     */
    val maxDepth = 100000000 // if DEBUG then 2 else 1000

    found = HashSet(url)

    crawlRecursive(HashSet.empty, Array(url), maxConnections, maxDepth)
  }
}
