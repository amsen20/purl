package org.http4s.curl

import munit.FunSuite
import org.http4s.curl.unsafe.CURLcode

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
