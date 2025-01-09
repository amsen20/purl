# pURL
A *HTTP* client on [Scala Native](https://github.com/scala-native/scala-native/), backed by [libcurl](https://curl.se/libcurl/) and [PollerBear](https://github.com/amsen20/pollerBear). Check out a simple [web crawler](https://github.com/amsen20/web-crawlers-bench/tree/main/single-threaded/src/main/scala).

- Compatible with different concurrency libraries, without the need for re-implementation (check out its interface to [Gears](https://github.com/lampepfl/gears) in [here](https://github.com/amsen20/purl/blob/main/gearsPurl/src/main/scala/GearsRequest.scala))
- Thread-safe, it works with passive pollers that are run on your custom thread-pool or pollerBear active poller.
- Written in fully direct Scala, which enables benefiting from all static analysis tools developed for Scala (or in its type system).

## Setup
After you have published the PollerBear library locally, you can enter the pollerBear version in `/project/Versions.scala`. Then you would be able to run the project, the following will run the tests:
```
$ sbt
sbt:root> startTestServer
sbt:root> test
```

## Usage
To use the pURL library in your project, first, you have to locally publish the library using the following command:
```
sbt publishLocal
```
Then, you can add the library to your dependencies as below:
```Scala
  libraryDependencies += "ca.uwaterloo.plg" %%% "purl" % purlVersion,
```

## API
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