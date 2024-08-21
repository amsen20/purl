package crawler

import gurl.http.*
import gurl.http.simple.*
import gurl.http.simple.{ HttpVersion, SimpleRequest }
import gurl.http.CurlRequest
import gurl.unsafe.CurlRuntimeContext
import pollerBear.runtime.Poller
import scala.util.*
import shared.TimeOut
import shared.WebCrawlerBase

class WebCrawler(
    using curlRuntimeContext: CurlRuntimeContext,
    poller: Poller
) extends WebCrawlerBase:

  override def getWebContent(url: String, onResponse: Option[String] => Unit): Unit =
    CurlRequest(
      SimpleRequest(
        HttpVersion.V2,
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
