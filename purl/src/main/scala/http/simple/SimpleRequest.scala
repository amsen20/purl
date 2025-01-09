package purl.http
package simple

case class SimpleRequest(
    httpVersion: HttpVersion,
    method: HttpMethod,
    headers: List[String],
    uri: String,
    body: String
)
