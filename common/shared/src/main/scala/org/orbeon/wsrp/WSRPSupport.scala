package org.orbeon.wsrp

import org.orbeon.io.CharsetNames
import org.orbeon.oxf.common.OXFException
import org.orbeon.oxf.util.PathUtils.UrlEncoderDecoder
import org.orbeon.oxf.util.PathUtils

import java.io.Writer
import java.net.URLDecoder
import java.{util => ju}
import scala.jdk.CollectionConverters._


object WSRPSupport {

  val PathParameterName           = "orbeon.path"

  val BaseTag                     = "wsrp_rewrite"
  val StartTag                    = BaseTag + '?'
  val EndTag                      = "/" + BaseTag
  val PrefixTag                   = BaseTag + '_'

  val BaseTagLength               = BaseTag.length
  val StartTagLength              = StartTag.length
  val EndTagLength                = EndTag.length
  val PrefixTagLength             = PrefixTag.length

  val URLTypeBlockingAction       = 1
  val URLTypeRender               = 2
  val URLTypeResource             = 3

  val URLTypeBlockingActionString = "blockingAction"
  val URLTypeRenderString         = "render"
  val URLTypeResourceString       = "resource"

  val URLTypeParam                = "wsrp-urlType"
  val ModeParam                   = "wsrp-mode"
  val WindowStateParam            = "wsrp-windowState"
  val NavigationalStateParam      = "wsrp-navigationalState"

  val URLTypes: Map[Int, String] = Map(
    URLTypeBlockingAction -> URLTypeBlockingActionString,
    URLTypeRender         -> URLTypeRenderString,
    URLTypeResource       -> URLTypeResourceString
  )

  // Parse a string containing WSRP encodings and encode the URLs and namespaces
  def decodeWSRPContent(content: String, ns: String, decodeURL: String => String, writer: Writer): Unit = {

    val stringLength = content.length
    var currentIndex = 0
    var index        = 0

    while ( {
      index = content.indexOf(BaseTag, currentIndex); index
    } != -1) {

      // Write up to the current mark
      writer.write(content, currentIndex, index - currentIndex)

      // Check if escaping is requested
      if (index + BaseTagLength * 2 <= stringLength &&
        content.substring(index + BaseTagLength, index + BaseTagLength * 2) == BaseTag) {
        // Write escaped tag, update index and keep looking
        writer.write(BaseTag)
        currentIndex = index + BaseTagLength * 2
      } else if (index < stringLength - BaseTagLength && content.charAt(index + BaseTagLength) == '?') {
        // URL encoding
        // Find the matching end mark
        val endIndex = content.indexOf(EndTag, index)
        if (endIndex == -1)
          throw new IllegalArgumentException("Missing end tag for WSRP encoded URL.")
        val encodedURL = content.substring(index + StartTagLength, endIndex)
        currentIndex = endIndex + EndTagLength

        writer.write(decodeURL(encodedURL))
      } else if (index < stringLength - BaseTagLength && content.charAt(index + BaseTagLength) == '_') {
        // Namespace encoding
        writer.write(ns)
        currentIndex = index + PrefixTagLength
      } else
        throw new IllegalArgumentException("Invalid WSRP rewrite tagging.")
    }

    // Write remainder of string
    if (currentIndex < stringLength)
      writer.write(content, currentIndex, content.length - currentIndex)
  }

  /**
   * Encode an URL into a WSRP pattern including the string "wsrp_rewrite".
   *
   * This does not call the portlet API. Used by Portlet2URLRewriter.
   */
  def encodeURL(
    urlType          : Int,
    navigationalState: String,
    mode             : String,
    windowState      : String,
    fragmentId       : String,
    secure           : Boolean)(implicit
    ed               : UrlEncoderDecoder
  ): String = {

    val sb = new StringBuilder(StartTag)

    sb.append(URLTypeParam)
    sb.append('=')

    val urlTypeString = URLTypes.getOrElse(urlType, throw new IllegalArgumentException)

    sb.append(urlTypeString)

    // Encode mode
    if (mode ne null) {
      sb.append('&')
      sb.append(ModeParam)
      sb.append('=')
      sb.append(mode)
    }

    // Encode window state
    if (windowState ne null) {
      sb.append('&')
      sb.append(WindowStateParam)
      sb.append('=')
      sb.append(windowState)
    }

    // Encode navigational state
    if (navigationalState ne null) {
      sb.append('&')
      sb.append(NavigationalStateParam)
      sb.append('=')
      sb.append(ed.encode(navigationalState))
    }
    sb.append(EndTag)

    sb.toString
  }

  type CreateResourceURL = String => String
  type CreatePortletURL = (Option[String], Option[String], ju.Map[String, Array[String]]) => String

  def decodeURL(
    encodedURL       : String,
    createResourceURL: CreateResourceURL,
    createActionURL  : CreatePortletURL,
    createRenderURL  : CreatePortletURL)(implicit
    ed               : UrlEncoderDecoder
  ): String = {


    def removeAmpIfNeeded(s: String) =
      if (s.startsWith("amp;")) s.substring("amp;".length) else s

    val wsrpParameters = PathUtils.decodeQueryStringPortlet(encodedURL)

    val urlType = {
      val urlType = getFirstValueFromStringArray(wsrpParameters.get(URLTypeParam))

      if (urlType eq null)
        throw new IllegalArgumentException(s"Missing URL type for WSRP encoded URL $encodedURL")

      if (!URLTypes.values.toSet(urlType))
        throw new IllegalArgumentException(s"Invalid URL type $urlType for WSRP encoded URL $encodedURL")

      urlType
    }

    val navigationParameters = {
      val navigationalStateValue = getFirstValueFromStringArray(wsrpParameters.get(NavigationalStateParam))
      if (navigationalStateValue ne null)
        PathUtils.decodeQueryStringPortlet(URLDecoder.decode(removeAmpIfNeeded(navigationalStateValue), CharsetNames.Utf8))
      else
        ju.Collections.emptyMap[String, Array[String]]
    }

    if (urlType == URLTypeResourceString) {
      val resourcePath = navigationParameters.get(PathParameterName)(0)
      navigationParameters.remove(PathParameterName)
      val resourceQuery = PathUtils.encodeQueryString(navigationParameters.asScala)
      val resourceId    = PathUtils.appendQueryString(resourcePath, resourceQuery)

      createResourceURL(resourceId)
    } else {
      val portletMode =
        Option(getFirstValueFromStringArray(wsrpParameters.get(ModeParam))) map removeAmpIfNeeded

      val windowState =
        Option(getFirstValueFromStringArray(wsrpParameters.get(WindowStateParam))) map removeAmpIfNeeded

      if (urlType == URLTypeBlockingActionString)
        createActionURL(portletMode, windowState, navigationParameters)
      else
        createRenderURL(portletMode, windowState, navigationParameters)
    }
  }

  def getFirstValueFromStringArray(values: Array[String]): String =
    if (values != null && values.length > 0)
      values(0)
    else
      null
}