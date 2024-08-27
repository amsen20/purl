package purl.http
package simple

enum HttpVersion(raw: String):
  case V1_0 extends HttpVersion("HTTP/1.0")
  case V1_1 extends HttpVersion("HTTP/1.1")
  case V2 extends HttpVersion("HTTP/2")
  case V3 extends HttpVersion("HTTP/3")

  override def toString(): String = raw

object HttpVersion:
  def fromString(raw: String): Option[HttpVersion] = raw match
      case "HTTP/1.0" => Some(V1_0)
      case "HTTP/1.1" => Some(V1_1)
      case "HTTP/2" => Some(V2)
      case "HTTP/3" => Some(V3)
      case _ => None

enum HttpMethod(raw: String):
  case GET extends HttpMethod("GET")
  case POST extends HttpMethod("POST")
  case PUT extends HttpMethod("PUT")
  case DELETE extends HttpMethod("DELETE")
  case PATCH extends HttpMethod("PATCH")
  case HEAD extends HttpMethod("HEAD")
  case OPTIONS extends HttpMethod("OPTIONS")
  case TRACE extends HttpMethod("TRACE")

  override def toString(): String = raw