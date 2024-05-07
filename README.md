# gurl

## Introduction
A *HTTP* client on [Scala Native](https://github.com/scala-native/scala-native/), backed by [libcurl](https://curl.se/libcurl/). Check out the a simple [web crawler](https://github.com/amsen20/gurl/blob/direct_scala/example/src/main/scala/Main.scala).

- Non-blocking, with support for running multiple concurrent requests in parallel
- Multi-threaded, Can be used by wrapping the requests in any [gears](https://github.com/lampepfl/gears) Source types (like Future) and can be executed in multiple threads
- Direct Scala, used in a direct scala way with no need for complicated monads or implicits.

## Setup
TBD, The library will be published whenever [gears](https://github.com/lampepfl/gears) is published. 

## Usage
- A simple get request to a URL:
```scala
CurlMultiRuntime: // the context for curl-multi handle
  Async.blocking: // the context for gears concurrency
  val res = CurlRequest(
    SimpleRequest(
      HttpVersion.V1_0,
      HttpMethod.GET,
      List(),
      "http://some.website",
      "".getBytes(),
    )
  ).get
  
  println("Status: " + res.status)
  println("Body: " + res.body.map(_.toChar).mkString)

```
- Multiple requests to multiple endpoints to get the fastest response:
```scala
CurlMultiRuntime:
  Async.blocking:
    val urls = List(/* some endpoints */)
    val futures = urls.map(url => Future:
      (CurlRequest(SimpleRequest(
        HttpVersion.V1_0,
        HttpMethod.GET,
        List(),
        url,
        "".getBytes(),
      )), url)
    )
    // Gets the fastest response while cancelling other requests after the first response is received.
    val fastestResponse = futures.awaitFirst
    println("The fastest endpoint is: " + fastestResponse._2)

```