package org.http4s.curl.http.simple

case class SimpleResponse(
    httpVersion: HttpVersion,
    status: Int,
    headers: List[Array[Byte]],
    trailers: List[Array[Byte]],
    body: Array[Byte],
)
