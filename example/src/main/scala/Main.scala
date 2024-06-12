package crawler

import gurl.multi.CurlMultiRuntime

import shared.*

@main def run(url: String, timeout: Long, maxConnections: Int): Unit =
  println("Using curl version: " + CurlMultiRuntime.curlVersionTriple.toString())
  CurlMultiRuntime(maxConnections, Int.MaxValue):
    val crawler = WebCrawler()
    Experiment.run(crawler, url, timeout, maxConnections)
