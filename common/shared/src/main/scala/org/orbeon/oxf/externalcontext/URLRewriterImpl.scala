package org.orbeon.oxf.externalcontext

import org.orbeon.oxf.common.OXFException
import org.orbeon.oxf.util.MarkupUtils._
import org.orbeon.oxf.util.{PathUtils, StringUtils}

import java.net.{URI, URISyntaxException}


object URLRewriterImpl {

  def rewriteURL(request: ExternalContext.Request, urlString: String, rewriteMode: Int): String =
    rewriteURL(
      scheme       = request.getScheme,
      host         = request.getServerName,
      port         = request.getServerPort,
      contextPath  = request.getClientContextPath(urlString),
      requestPath  = request.getRequestPath,
      rawUrlString = urlString,
      rewriteMode  = rewriteMode
    )

  def rewriteServiceURL(
    request         : ExternalContext.Request,
    urlString       : String,
    rewriteMode     : Int,
    baseURIProperty : String
  ): String = {

    // NOTE: We used to assert here that the mode required an absolute URL, but as of 2016-03-17 one caller
    // wants a path.

    // Case where a protocol is specified: the URL is left untouched
    if (PathUtils.urlHasProtocol(urlString)) {
      urlString
    } else if (baseURIProperty.isBlank) {
      // Property not specified, use request to build base URI
      rewriteURL(
        request.getScheme,
        request.getServerName,
        request.getServerPort,
        request.getClientContextPath(urlString),
        request.getRequestPath, urlString,
        rewriteMode
      )
    } else {
      // Property specified

      val baseURI =
        try
          new URI(StringUtils.trimAllToEmpty(baseURIProperty))
        catch {
          case t: URISyntaxException =>
            throw new OXFException(s"Incorrect base URI property specified: `$baseURIProperty`", t)
        }

      // NOTE: Force absolute URL to be returned in this case anyway
      rewriteURL(
        if (baseURI.getScheme != null)
          baseURI.getScheme
        else
          request.getScheme,
        if (baseURI.getHost != null)
          baseURI.getHost
        else request.getServerName,
        if (baseURI.getHost != null)
          baseURI.getPort
        else
          request.getServerPort,
        baseURI.getPath,
        "",
        urlString,
        rewriteMode
      )
    }
  }

  def rewriteURL(
    scheme       : String,
    host         : String,
    port         : Int,
    contextPath  : String,
    requestPath  : String,
    rawUrlString : String,
    rewriteMode  : Int
  ): String = {

    // Accept human-readable URI
    val urlString = rawUrlString.encodeHRRI(processSpace = true)

    if (PathUtils.urlHasProtocol(urlString)) {
      // Case where a protocol is specified: the URL is left untouched (except for human-readable processing)
       urlString
    } else {
      val baseURLString = {

        // Prepend absolute base if needed
        val _baseURLString =
          if ((rewriteMode & URLRewriter.REWRITE_MODE_ABSOLUTE) != 0)
            scheme + "://" + host + (if (port == 80 || port == -1) "" else ":" + port)
          else
            ""

        // Append context path if needed
        if ((rewriteMode & URLRewriter.REWRITE_MODE_ABSOLUTE_PATH_NO_CONTEXT) == 0)
          _baseURLString + contextPath
        else
          _baseURLString
      }

      // Return absolute path URI with query string and fragment identifier if needed
      if (urlString.startsWith("?")) {
        // This is a special case that appears to be implemented
        // in Web browsers as a convenience. Users may use it.
        baseURLString + requestPath + urlString
      } else if ((rewriteMode & URLRewriter.REWRITE_MODE_ABSOLUTE_PATH_OR_RELATIVE) != 0 && ! urlString.startsWith("/") && "" != urlString) {
        // Don't change the URL if it is a relative path and we don't force absolute
        urlString
      } else {
        // Regular case, parse the URL
        val baseURIWithPath = new URI("http", "example.org", requestPath, null)
        val resolvedURI = baseURIWithPath.resolve(urlString).normalize // normalize to remove "..", etc.
        // Append path, query and fragment
        val query = resolvedURI.getRawQuery
        val fragment = resolvedURI.getRawFragment
        val tempResult =
          resolvedURI.getRawPath + (
            if (query != null)
              "?" + query
            else
              ""
          ) + (
            if (fragment != null)
              "#" + fragment
            else
              ""
          )

        // Prepend base
        baseURLString + tempResult
      }
    }
  }
}