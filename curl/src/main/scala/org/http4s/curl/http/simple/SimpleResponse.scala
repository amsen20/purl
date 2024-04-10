package org.http4s.curl.http.simple

case class SimpleResponse(
    httpVersion: HttpVersion,
    status: Int,
    headers: List[String],
    trailers: List[String],
    body: String,
)
