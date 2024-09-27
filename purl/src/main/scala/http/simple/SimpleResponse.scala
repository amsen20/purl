package purl.http
package simple

import purl.internal.FastNativeString

case class SimpleResponse(
    httpVersion: HttpVersion,
    status: Int,
    headers: List[String],
    trailers: List[String],
    body: FastNativeString
)
