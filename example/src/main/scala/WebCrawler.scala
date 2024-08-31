package crawler

import pollerBear.runtime.PassivePoller
import purl.http.*
import purl.http.simple.*
import purl.http.simple.{ HttpVersion, SimpleRequest }
import purl.http.CurlRequest
import purl.unsafe.CurlRuntimeContext
import scala.util.*
import shared.TimeOut
import shared.WebCrawlerBase

class WebCrawler(
    using curlRuntimeContext: CurlRuntimeContext,
    poller: PassivePoller
) extends WebCrawlerBase:

  override def getWebContent(url: String, onResponse: Option[String] => Unit): Unit =
    CurlRequest(
      SimpleRequest(
        HttpVersion.V1_1,
        HttpMethod.GET,
        List(),
        url,
        "".getBytes()
      )
    )(res =>
      res match
        case Success(res) =>
          if res.status != 200 then None
          if !res.headers
              .map(_.map(_.toChar).mkString)
              .map(header => header.contains("content-type") && header.contains("text/html"))
              .reduce(_ || _)
          then None
          onResponse(Some(res.body.map(_.toChar).mkString))
        case Failure(e) =>
          // e.printStackTrace()
          onResponse(None)
    )

  override def awaitResponses(timeout: Long): Unit =
    if timeout < 0 then throw new TimeOut()
    poller.waitUntil()
