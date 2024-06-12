package shared

import scala.concurrent.ExecutionContext
import scala.concurrent.duration

import gears.async.*
import gears.async.default.given
import scala.concurrent.duration.FiniteDuration

object Experiment:
  given ExecutionContext = ExecutionContext.global

  def run(crawler: WebCrawlerBase, url: String, timeout: Long, maxConnections: Int): Unit =
    val startTime = System.currentTimeMillis()
    Async.blocking:
      withTimeoutOption(FiniteDuration(timeout, duration.MILLISECONDS)):
        crawler.crawl(url, maxConnections)
    val endTime = System.currentTimeMillis()
    val elapsedTime = endTime - startTime

    println(s"explored=${crawler.successfulExplored.size}")
    println(s"found=${crawler.found.size}")
    println(s"totalChars=${crawler.charsDownloaded}")
    println(s"overheadTime=${elapsedTime - timeout}")

    println("Explored links:")
    crawler.successfulExplored.foreach(println(_))
    if DEBUG then
      println("Explored links:")
      crawler.successfulExplored.foreach(println(_))
      println("Found links:")
      crawler.found.foreach(println(_))
