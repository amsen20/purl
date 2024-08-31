package purl
package global

import purl.unsafe._

final class CurlGlobalContext

object CurlGlobal {
  def apply[T](body: CurlGlobalContext ?=> T): T = 
    val initCode = libcurl.curl_global_init(2)
    if (initCode.isError)
      throw CurlError.fromCode(initCode)
    
    try body(using CurlGlobalContext())
    finally libcurl.curl_global_cleanup()
}