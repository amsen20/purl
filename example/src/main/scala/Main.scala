package crawler

import gurl.multi.CurlMultiRuntime
import pollerBear.runtime._
import shared.*

@main def run(url: String, timeout: Long, maxConnections: Int): Unit =
  println("Using curl version: " + CurlMultiRuntime.curlVersionTriple.toString())
  SingleThreadedPoller { poller =>
    given Poller = poller
    CurlMultiRuntime(maxConnections, Int.MaxValue):
      // TODO make a sugar API for this
      poller.registerOnDeadline(
        System.currentTimeMillis() + timeout,
        {
          case Some(e: PollerCleanUpException) => false
          case Some(e)                         => false
          case None                            => throw TimeOut()
        }
      )

      val crawler = WebCrawler()
      Experiment.run(crawler, url, timeout, maxConnections)
  }
