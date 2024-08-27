package gurl
package multi

sealed abstract class CurlMultiError(msg: String) extends RuntimeException(msg)
case object CurlMultiCleanUpError extends CurlMultiError("cleaning up the curl runtime")
