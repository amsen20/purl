package purl

import munit.FunSuite
import purl.unsafe.CURLcode
import purl.CurlError

class ErrorCodeSuite extends FunSuite {
  test("sanity") {
    val error = purl.CurlError.fromCode(CURLcode(1))
    assert(error.info.contains("Unsupported protocol"))
  }

  test("sanity") {
    val error = purl.CurlError.fromCode(CURLcode(7))
    assert(error.info.contains("Couldn't connect to server"))
  }
}
