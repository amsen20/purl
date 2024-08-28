package crawler

import purl.multi.CurlMultiRuntime
import pollerBear.runtime._
import shared.*

@main def run(url: String, timeout: Long, maxConnections: Int): Unit =
  println("Using curl version: " + CurlMultiRuntime.curlVersionTriple.toString())
  withPassivePoller { poller =>
    given PassivePoller = poller
    CurlMultiRuntime:
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
