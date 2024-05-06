package ca.uwaterloo.plg.curl

import scala.scalanative.unsafe.fromCString

import unsafe.libcurl._

sealed abstract class CurlError(msg: String) extends RuntimeException(msg)

object CurlError {
  final case class CurlEasyError(code: CURLcode, info: String, details: Option[String] = None)
      extends CurlError(
        s"curl responded with error code: ${code.value}\ninfo: $info${details.map(d => s"\ndetails: $d").getOrElse("")}"
      )
  final case class CurlMultiError(code: CURLMcode, info: String)
      extends CurlError(
        s"curl multi interface responded with error code: ${code.value}\n$info"
      )

  private[curl] def fromCode(code: CURLcode) = {
    val info = fromCString(curl_easy_strerror(code))
    new CurlEasyError(code, info)
  }
  private[curl] def fromCode(code: CURLcode, details: String) = {
    val info = fromCString(curl_easy_strerror(code))
    new CurlEasyError(code, info, Some(details))
  }

  private[curl] def fromMCode(code: CURLMcode) = {
    val info = fromCString(curl_multi_strerror(code))
    new CurlMultiError(code, info)
  }
}
