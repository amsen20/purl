package shared

import scala.concurrent.duration
import scala.concurrent.duration.FiniteDuration

object Experiment:
  def run(crawler: WebCrawlerBase, url: String, timeout: Long, maxConnections: Int): Unit =
    val startTime = System.currentTimeMillis()

    try crawler.crawl(url, maxConnections, timeout)
    catch case e: TimeOut => ()

    val endTime = System.currentTimeMillis()
    val elapsedTime = endTime - startTime

    println(s"explored=${crawler.successfulExplored.size}")
    println(s"found=${crawler.found.size}")
    println(s"totalChars=${crawler.charsDownloaded}")
    println(s"overheadTime=${elapsedTime - timeout}")

    if DEBUG then
      println("Explored links:")
      crawler.successfulExplored.foreach(println(_))
      println("Found links:")
      crawler.found.foreach(println(_))
