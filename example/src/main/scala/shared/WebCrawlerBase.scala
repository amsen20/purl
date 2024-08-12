package shared

import scala.io.Source
import scala.util._
import scala.math.min
import scala.concurrent.duration

import scala.compiletime.ops.double
import scala.annotation.tailrec
import scala.collection.immutable.HashSet

abstract class WebCrawlerBase {
  var found = HashSet[String]()
  var successfulExplored = HashSet[String]()

  // Only for analysis
  var charsDownloaded = 0

  var deadline: Long = -1

  def getWebContent(url: String, onResponse: Option[String] => Unit): Unit = ???
  def awaitResponses(timeout: Long): Unit = ???

  def exploreLayer(
      seen: HashSet[String],
      layer: List[String],
      maxConnections: Int,
  ): List[String] = {
    var nextLayer: HashSet[String] = HashSet()
    var finished = 0
    var started = 0

    val onResponse = (url: String, response: Option[String]) =>
      finished += 1

      response match
        case Some(content) =>
          successfulExplored = successfulExplored + url
          charsDownloaded += content.length

          val links = UrlUtils.extractLinks(url, content)
          found = found ++ links
          nextLayer = nextLayer ++ links
        case None => ()
      ()

    while finished < layer.size do
      while started < layer.size && started - finished < maxConnections do
        val url = layer(started)
        started += 1
        getWebContent(url, onResponse(url, _))

      awaitResponses(deadline - System.currentTimeMillis())
    end while

    (nextLayer -- seen).toList
  }

  @tailrec
  final def crawlRecursive(
      seen: HashSet[String],
      layer: List[String],
      maxConnections: Int,
      depth: Int,
  ): Unit =
    if depth != 0 then
      val nextLayer = exploreLayer(seen, layer, maxConnections)
      crawlRecursive(
        seen ++ nextLayer,
        nextLayer,
        maxConnections,
        depth - 1,
      )

  def crawl(url: String, maxConnections: Int, timeout: Long): Unit = {
    /*
      TODO Make the function return a lazy list
      which returns found links one by one,
      then the maxDepth can be removed.
     */
    val maxDepth = 100000000 // if DEBUG then 2 else 1000

    deadline = System.currentTimeMillis() + timeout
    found = HashSet(url)

    crawlRecursive(HashSet.empty, List(url), maxConnections, maxDepth)
  }

}
