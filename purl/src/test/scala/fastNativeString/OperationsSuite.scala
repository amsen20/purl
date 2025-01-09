package purl

import munit.FunSuite
import purl.internal.FastNativeString

class OperationsSuite extends FunSuite {
  test("simple string creation") {
    val s = FastNativeString("hello")
    assert(s.toString == "hello")
  }

  test("append") {
    val s = FastNativeString("hello")
    s.append(" world")
    assert(s.toString == "hello world")
  }

  test("substring") {
    val s   = FastNativeString("hello world")
    val sub = s.substring(6, 11)
    assert(sub.toString == "world")
  }

  test("multiple operations") {
    val s = FastNativeString("hello world")
    val world = FastNativeString("world")
    val l = s.indexOf(world, 0)
    val r = s.indexOf('d', l)
    val sub = s.substring(l, r)
    assert(sub.toString == "worl")
  }
}
