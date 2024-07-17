package gurl
package logger

// TODO:
// - Add multiple log levels
// - Add a way to log to a file
inline val enableLogging = true

private[gurl] object GLogger:
  inline def log(msg: String): Unit =
    inline if enableLogging then
      print("[gURL] ")
      // print("[at " + java.time.LocalTime.now() + "] ")
      print(s"[${Thread.currentThread().getName()}] ")
      println(msg)
