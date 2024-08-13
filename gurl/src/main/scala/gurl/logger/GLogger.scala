
package gurl
package logger

// TODO:
// - Add multiple log levels
// - Add a way to log to a file
inline val enableLogging = false

final val SECOND = 1000
final val MINUTE = 60 * SECOND
final val HOUR = 60 * MINUTE

/*private[gurl]*/ object GLogger:
  inline def log(msg: String): Unit =
    inline if enableLogging then
      print("[gURL] ")
      // print("[at " + java.time.LocalTime.now() + "] ")
      print(s"[${Thread.currentThread().getName()}] ")
      val time = System.currentTimeMillis() % HOUR
      print(s"[${time / MINUTE}:${time % MINUTE / SECOND}:${time % SECOND}] ")
      println(msg)