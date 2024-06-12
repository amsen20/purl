package gurl

import munit.FunSuite
import gurl.unsafe.CURLcode
import gurl.CurlError

class ErrorCodeSuite extends FunSuite {
  test("sanity") {
    val error = gurl.CurlError.fromCode(CURLcode(1))
    assert(error.info.contains("Unsupported protocol"))
  }

  test("sanity") {
    val error = gurl.CurlError.fromCode(CURLcode(7))
    assert(error.info.contains("Couldn't connect to server"))
  }
}
