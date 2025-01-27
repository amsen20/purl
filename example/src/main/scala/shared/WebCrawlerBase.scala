package shared

import pollerBear.logger.PBLogger
import purl.internal.FastNativeString
import scala.annotation.tailrec
import scala.collection.immutable.HashSet
import scala.compiletime.ops.double
import scala.concurrent.duration
import scala.io.Source
import scala.math.min
import scala.util._

abstract class WebCrawlerBase {
  var found              = HashSet[String]()
  var successfulExplored = HashSet[String]()

  // Only for analysis
  var charsDownloaded = 0

  var deadline: Long = -1

  def getWebContent(url: String, onResponse: Option[FastNativeString] => Unit): Unit = ???
  def awaitResponses(timeout: Long): Unit                                            = ???

  def exploreLayer(
      seen: HashSet[String],
      layer: List[String],
      maxConnections: Int
  ): List[String] = {
    var nextLayer: HashSet[String] = HashSet()
    var finished                   = 0
    var started                    = 0

    val iter      = layer.iterator
    val layerSize = layer.size
    PBLogger.log(s"Exploring layer of size ${layerSize}")

    def onResponse: (String, Option[FastNativeString]) => Unit =
      (url: String, response: Option[FastNativeString]) =>
        PBLogger.log(s"response for $url received")
        val time = System.currentTimeMillis()
        // println("finished: " + finished + "  at " + time)
        finished += 1

        response match
          case Some(content) =>
            PBLogger.log(s"some content was there")
            successfulExplored = successfulExplored + url
            charsDownloaded += content.length

            PBLogger.log(s"extracting links")
            val links = UrlUtils.extractLinks(url, content)
            PBLogger.log(s"extracted links")
            found = found ++ links
            nextLayer = nextLayer ++ links
            PBLogger.log(s"added links")

            while started < layerSize && started - finished < maxConnections do
              val url = iter.next()
              started += 1
              val time = System.currentTimeMillis()
              getWebContent(url, onResponse(url, _))
          case None => ()
        ()

    while finished < layerSize do
      while started < layerSize && started - finished < maxConnections do
        val url = iter.next()
        started += 1
        val time = System.currentTimeMillis()
        getWebContent(url, onResponse(url, _))

      // println("vvvvvvvvvvvvvvvvvvvvvv")
      // println("alive connections before: " + (started - finished))
      // val timeBeforeAwait = System.currentTimeMillis()
      awaitResponses(deadline - System.currentTimeMillis())
      // val timeAfterAwait = System.currentTimeMillis()
      // println("alive connections after: " + (started - finished))
      // println("Awaited for: " + (timeAfterAwait - timeBeforeAwait))
      // println("^^^^^^^^^^^^^^^^^^^^^^")
      // println( s"Finished: $finished, Started: $started, Layer size: ${layerSize}")
    end while

    (nextLayer -- seen).toList
  }

  var checkPointTime = -1L
  var startUrl       = ""

  @tailrec
  final def crawlRecursive(
      seen: HashSet[String],
      layer: List[String],
      maxConnections: Int,
      depth: Int
  ): Unit =
    if System.currentTimeMillis() - checkPointTime > 100000 then
      println(s"Found: ${found.size}, Explored: ${successfulExplored.size}, Time: ${System
          .currentTimeMillis() - checkPointTime}")
      checkPointTime = System.currentTimeMillis()

      found = HashSet(startUrl)
      successfulExplored = HashSet.empty
      charsDownloaded = 0

      crawlRecursive(
        HashSet.empty,
        List(startUrl),
        maxConnections,
        depth
      )
    else if depth != 0 then
      val nextLayer = exploreLayer(seen, layer, maxConnections)
      crawlRecursive(
        seen ++ nextLayer,
        nextLayer,
        maxConnections,
        depth - 1
      )

  def crawl(url: String, maxConnections: Int, timeout: Long): Unit = {
    /*
      TODO Make the function return a lazy list
      which returns found links one by one,
      then the maxDepth can be removed.
     */
    val maxDepth = 100000000 // if DEBUG then 2 else 1000

    checkPointTime = System.currentTimeMillis()
    startUrl = url
    deadline = System.currentTimeMillis() + timeout
    found = HashSet(url)

    crawlRecursive(HashSet.empty, List(url), maxConnections, maxDepth)
  }

}
