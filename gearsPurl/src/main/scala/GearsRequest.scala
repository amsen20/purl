package gearsPurl

import gears.async._
import gears.async.Future.Promise
import java.util.concurrent.CancellationException
import pollerBear.logger.PBLogger
import pollerBear.runtime.ActivePoller
import purl.http.simple._
import purl.http.CurlRequest
import purl.unsafe.CurlRuntimeContext
import scala.util._

def request(req: SimpleRequest)(
    using CurlRuntimeContext,
    Async
): Try[SimpleResponse] =
  val response: Future.Promise[SimpleResponse] = Future.Promise()

  val cc        = summon[CurlRuntimeContext]
  val requestID = cc.startRequest(req, response.complete(_))

  try
    response.asFuture.awaitResult
  catch {
    case e: CancellationException =>
      PBLogger.log("CALLING CANCELLATION MYSELF")
      cc.cancelRequest(requestID)
      throw e
    case e: Throwable =>
      cc.cancelRequest(requestID)
      Failure(e)
  }
