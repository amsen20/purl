package shared

import pollerBear.logger.PBLogger
import purl.internal.FastNativeString
import scala.collection.immutable.HashSet

object UrlUtils {

  var totalElapsedTime = 0L

  def isValidURL(url: String, baseURL: String): Boolean =
    val prefix       = url.startsWith("http://") || url.startsWith("https://")
    val noParams     = !url.contains("?")
    val noColon      = true // !url.slice(url.indexOf("://") + 3, url.length).contains(":")
    val onMainDomain = getBaseURL(url).equals(baseURL)
    prefix && noParams && noColon && onMainDomain

  def ifValid(url: String, baseURL: String): Option[String] =
    if isValidURL(url, baseURL) then Some(url) else None

  def getBaseURL(url: String): String =
    val idx = url.indexOf("/", url.indexOf("://") + 3)
    if idx == -1 then "!!SOMETHING_THAT_NEVER_MATCHES!!" else url.substring(0, idx)

  def removeParams(url: String): String =
    val index = url.indexOf('?')
    if index != -1 then url.substring(0, index) else url

  def cleanURL(url: String, baseURL: String): Option[String] =
    val noParamURL = removeParams(url)

    if url.startsWith("/") then
      // relative URL
      ifValid(baseURL + noParamURL, baseURL)
    else
      // absolute URL
      ifValid(noParamURL, baseURL)

  def findLinks(content: FastNativeString, from: Int): List[String] =
    content.indexOf(INDICATOR, from) match
      case -1 =>
        List()
      case ind =>
        val start     = ind + INDICATOR.length
        val separator = content(start)
        val end       = content.indexOf(separator, start + 1)
        if end != -1 then
          val urlFNS = content.substring(start + 1, end)
          val url    = urlFNS.toString()
          urlFNS.free()

          url +: findLinks(
            content,
            end + 1
          )
        else List()

  def extractLinks(url: String, content: FastNativeString): List[String] =
    val startTime = System.nanoTime()
    PBLogger.log(s"extracting links")
    val links = findLinks(content, 0)
    PBLogger.log(s"finding done")

    val baseURL = getBaseURL(url)
    PBLogger.log("baseURL")

    val targetLinks = links
      .map(cleanURL(_, baseURL))
      .foldLeft(List[String]())((acc, x) =>
        x match {
          case Some(value) => value +: acc
          case None        => acc
        }
      )
    PBLogger.log(s"target links found")

    val endTime     = System.nanoTime()
    val elapsedTime = endTime - startTime
    totalElapsedTime += elapsedTime

    targetLinks

}
