package crawler

import pollerBear.runtime._
import purl.global.CurlGlobal
import purl.http.simple.{ HttpMethod, HttpVersion, SimpleRequest, SimpleResponse }
import purl.multi.CurlMultiRuntime
import shared.*

@main def run(url: String, timeout: Long, maxConnections: Int): Unit =
  println("Using curl version: " + CurlMultiRuntime.curlVersionTriple.toString())
  CurlGlobal:
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
