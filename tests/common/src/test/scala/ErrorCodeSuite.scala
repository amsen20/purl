package ca.uwaterloo.plg.curl

import munit.FunSuite
import ca.uwaterloo.plg.curl.unsafe.CURLcode
import ca.uwaterloo.plg.curl.CurlError

class ErrorCodeSuite extends FunSuite {
  test("sanity") {
    val error = CurlError.fromCode(CURLcode(1))
    assert(error.info.contains("Unsupported protocol"))
  }

  test("sanity") {
    val error = CurlError.fromCode(CURLcode(7))
    assert(error.info.contains("Couldn't connect to server"))
  }
}
