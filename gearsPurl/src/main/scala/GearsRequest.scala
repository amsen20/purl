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
  val cancel                                   = CurlRequest(req)(response.complete(_))
  try
    response.asFuture.awaitResult
  catch {
    case e: CancellationException =>
      PBLogger.log("CALLING CANCELLATION MYSELF")
      PBLogger.log(cancel.toString())
      cancel(e)
      throw e
    case e: Throwable =>
      cancel(e)
      Failure(e)
  }
