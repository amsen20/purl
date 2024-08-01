package shared

import scala.collection.mutable
import scala.io.Source
import scala.util._
import scala.math.min
import scala.concurrent.duration

import scala.collection.mutable.ListBuffer
import scala.compiletime.ops.double

abstract class WebCrawlerBase {
  val found = mutable.Set[String]()
  val successfulExplored = mutable.Set[String]()

  // Only for analysis
  var charsDownloaded = 0

  var deadline: Long = -1

  def getWebContent(url: String, onResponse: Option[String] => Unit): Unit = ???
  def awaitResponses(timeout: Long): Unit = ???

  def exploreLayer(
      seen: Set[String],
      layer: Set[String],
      maxConnections: Int,
  ): Set[String] = {
    val nextLayer: mutable.Set[String] = mutable.Set()
    val layerIt = layer.iterator

    var finished = 0
    var started = 0

    def goNext(onResponse: (String, Option[String]) => Unit): Unit =
      if !layerIt.hasNext then return
      val url = layerIt.next()
      getWebContent(url, onResponse(url, _))

    def getOnResponse =
      started += 1

      (url: String, response: Option[String]) =>
        finished += 1

        response match
          case Some(content) =>
            successfulExplored.add(url)
            charsDownloaded += content.length

            val links = UrlUtils.extractLinks(url, content)
            found ++= links
            nextLayer ++= links
          case None => ()
        ()

    for _ <- 0 until min(maxConnections, layer.size) do goNext(getOnResponse)

    while finished < layer.size do
      while started < layer.size && started - finished < maxConnections do goNext(getOnResponse)
      awaitResponses(deadline - System.currentTimeMillis())
    end while

    nextLayer.filter(url => !seen.contains(url) && !layer.contains(url)).toSet
  }

  def crawl(url: String, maxConnections: Int, timeout: Long): Unit = {
    deadline = System.currentTimeMillis() + timeout

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
