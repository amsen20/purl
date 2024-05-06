package ca.uwaterloo.plg.curl.http
package simple

case class SimpleResponse(
    httpVersion: HttpVersion,
    status: Int,
    headers: List[Array[Byte]],
    trailers: List[Array[Byte]],
    body: Array[Byte],
)
