package purl
package multi

sealed abstract class CurlMultiError(msg: String) extends RuntimeException(msg)
case object CurlMultiCleanUpError        extends CurlMultiError("cleaning up the curl runtime")
case object CurlMultiRequestCancellation extends CurlMultiError("cancelling the curl request")
