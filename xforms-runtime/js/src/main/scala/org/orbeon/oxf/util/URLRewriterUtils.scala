package org.orbeon.oxf.util

import java.{util => ju}

import org.orbeon.oxf.externalcontext.ExternalContext.Request


object URLRewriterUtils {

  // TODO: placeholder, does it matter for Scala.js?
  def isResourcesVersioned = false

  def rewriteResourceURL(
    request      : Request,
    urlString    : String,
    pathMatchers : ju.List[PathMatcher],
    rewriteMode  : Int
  ): String = {
    println(s"xxx URLRewriterUtils.rewriteResourceURL called for $urlString")
    throw new NotImplementedError("rewriteResourceURL")
  }

  def rewriteServiceURL(
    request     : Request,
    urlString   : String,
    rewriteMode : Int
  ): String = {
    println(s"xxx URLRewriterUtils.rewriteServiceURL called for $urlString")
    if (PathUtils.urlHasProtocol(urlString))
      urlString
    else
      throw new NotImplementedError("rewriteServiceURL")
  }

  // Used by `rewriteResourceURL` for `XFormsOutputControl`.
  // Q: Does anything make sense there?
  def getPathMatchers: ju.List[PathMatcher] =
    ju.Collections.emptyList[PathMatcher]
}
