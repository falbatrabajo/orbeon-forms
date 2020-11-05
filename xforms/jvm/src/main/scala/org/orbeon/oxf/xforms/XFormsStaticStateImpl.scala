/**
 * Copyright (C) 2011 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms

import org.orbeon.datatypes.MaximumSize
import org.orbeon.dom.io.XMLWriter
import org.orbeon.dom.{Document, Element}
import org.orbeon.oxf.common.Version
import org.orbeon.oxf.processor.generator.RequestGenerator
import org.orbeon.oxf.util.StaticXPath.CompiledExpression
import org.orbeon.oxf.util.StringUtils._
import org.orbeon.oxf.util._
import org.orbeon.oxf.xforms.XFormsProperties._
import org.orbeon.oxf.xforms.XFormsStaticStateImpl.StaticStateDocument
import org.orbeon.oxf.xforms.analysis._
import org.orbeon.oxf.xforms.state.AnnotatedTemplate
import org.orbeon.oxf.xforms.{XFormsProperties => P}
import org.orbeon.oxf.xml._
import org.orbeon.saxon.`type`.BuiltInAtomicType
import org.orbeon.saxon.sxpath.XPathExpression
import org.orbeon.xforms.XFormsNames._
import org.orbeon.xforms.xbl.Scope

import scala.jdk.CollectionConverters._


class XFormsStaticStateImpl(
  val encodedState        : String,
  val digest              : String,
  val startScope          : Scope,
  metadata                : Metadata,
  val template            : Option[AnnotatedTemplate], // for tests only?
  val staticStateDocument : StaticStateDocument
) extends XFormsStaticState {

  require(encodedState ne null)
  require(digest ne null)

  implicit val getIndentedLogger = Loggers.getIndentedLogger("analysis")

  // Create top-level part once `val`s are all initialized
  val topLevelPart: TopLevelPartAnalysis =
    PartAnalysisBuilder(this, None, startScope, metadata, staticStateDocument)

  def functionLibrary = topLevelPart.functionLibrary

  // Properties
  // These are `lazy val`s because they depend on the default model being found, which is done when
  // the `PartAnalysisImpl` is created above. Yes, this is tricky and not ideal.
  lazy val allowedExternalEvents   = staticStringProperty(P.ExternalEventsProperty).tokenizeToSet
  lazy val isHTMLDocument          = staticStateDocument.isHTMLDocument
  lazy val isXPathAnalysis         = Version.instance.isPEFeatureEnabled(staticBooleanProperty(P.XpathAnalysisProperty),     P.XpathAnalysisProperty)
  lazy val isCalculateDependencies = Version.instance.isPEFeatureEnabled(staticBooleanProperty(P.CalculateAnalysisProperty), P.CalculateAnalysisProperty)
  lazy val sanitizeInput           = StringReplacer(staticStringProperty(P.SanitizeProperty))
  lazy val isInlineResources       = staticBooleanProperty(P.InlineResourcesProperty)

  lazy val assets: XFormsAssets =
    XFormsAssetsBuilder.updateAssets(
      XFormsAssetsBuilder.fromJSONProperty,
      staticStringProperty(P.AssetsBaselineExcludesProperty),
      staticStringProperty(P.AssetsBaselineUpdatesProperty)
    )

  lazy val uploadMaxSize: MaximumSize =
    staticStringProperty(UploadMaxSizeProperty).trimAllToOpt flatMap
      MaximumSize.unapply orElse
      MaximumSize.unapply(RequestGenerator.getMaxSizeProperty.toString) getOrElse
      MaximumSize.LimitedSize(0L)

  lazy val uploadMaxSizeAggregate: MaximumSize =
    staticStringProperty(UploadMaxSizeAggregateProperty).trimAllToOpt flatMap
      MaximumSize.unapply getOrElse
      MaximumSize.UnlimitedSize

  lazy val uploadMaxSizeAggregateExpression: Option[CompiledExpression] = {

    val compiledExpressionOpt =
      for {
        rawProperty <- staticStringProperty(UploadMaxSizeAggregateExpressionProperty).trimAllToOpt
        model       <- topLevelPart.defaultModel // ∃ property => ∃ model, right?
      } yield
        XPath.compileExpression(
          xpathString      = rawProperty,
          namespaceMapping = model.namespaceMapping,
          locationData     = null,
          functionLibrary  = topLevelPart.functionLibrary,
          avt              = false
        )

    def getExpressionType(expr: XPathExpression) = {
      val internalExpr = expr.getInternalExpression
      internalExpr.getItemType(internalExpr.getExecutable.getConfiguration.getTypeHierarchy)
    }

    compiledExpressionOpt match {
      case Some(CompiledExpression(expr, _, _)) if getExpressionType(expr) == BuiltInAtomicType.INTEGER =>
        compiledExpressionOpt
      case Some(_) =>
        throw new IllegalArgumentException(s"property `$UploadMaxSizeAggregateExpressionProperty` must return `xs:integer` type")
      case None =>
        None
    }
  }

  def isClientStateHandling = staticStringProperty(P.StateHandlingProperty) == P.StateHandlingClientValue
  def isServerStateHandling = staticStringProperty(P.StateHandlingProperty) == P.StateHandlingServerValue

  private lazy val nonDefaultPropertiesOnly: Map[String, Either[Any, CompiledExpression]] =
    staticStateDocument.nonDefaultProperties map { case (name, (rawPropertyValue, isInline)) =>
      name -> {
        val maybeAVT = XMLUtils.maybeAVT(rawPropertyValue)
        topLevelPart.defaultModel match {
          case Some(model) if isInline && maybeAVT =>
            Right(XPath.compileExpression(rawPropertyValue, model.namespaceMapping, null, topLevelPart.functionLibrary, avt = true))
          case None if isInline && maybeAVT =>
            throw new IllegalArgumentException("can only evaluate AVT properties if a model is present") // 2016-06-27: Uncommon case but really?
          case _ =>
            Left(P.SupportedDocumentProperties(name).parseProperty(rawPropertyValue))
        }
      }
    }

  // For properties which can be AVTs
  def propertyMaybeAsExpression(name: String): Either[Any, CompiledExpression] =
    nonDefaultPropertiesOnly.getOrElse(name, Left(P.SupportedDocumentProperties(name).defaultValue))

  // For properties known to be static
  private def staticPropertyOrDefault(name: String) =
    staticStateDocument.nonDefaultProperties.get(name) map
      (_._1)                                           map
      P.SupportedDocumentProperties(name).parseProperty      getOrElse
      P.SupportedDocumentProperties(name).defaultValue

  def staticProperty       (name: String) = staticPropertyOrDefault(name: String)
  def staticStringProperty (name: String) = staticPropertyOrDefault(name: String).toString
  def staticBooleanProperty(name: String) = staticPropertyOrDefault(name: String).asInstanceOf[Boolean]
  def staticIntProperty    (name: String) = staticPropertyOrDefault(name: String).asInstanceOf[Int]

  // 2020-09-10: Used by `ScriptBuilder` only.
  def clientNonDefaultProperties: Map[String, Any] =
    for {
      (propertyName, _) <- staticStateDocument.nonDefaultProperties
      if SupportedDocumentProperties(propertyName).propagateToClient
    } yield
      propertyName -> staticProperty(propertyName)
}

object XFormsStaticStateImpl {



  // Represent the static state XML document resulting from the extractor
  //
  // - The underlying document produced by the extractor used to be further transformed to extract various documents.
  //   This is no longer the case and the underlying document should be considered immutable (it would be good if it
  //   was in fact immutable).
  // - The template, when kept for full update marks, is stored in the static state document as Base64.
  class StaticStateDocument(val xmlDocument: Document) {

    require(xmlDocument ne null)

    private def staticStateElement = xmlDocument.getRootElement

    // Pointers to nested elements
    def rootControl: Element = staticStateElement.element("root")
    def xblElements = rootControl.jElements(XBL_XBL_QNAME).asScala

    // TODO: if staticStateDocument contains XHTML document, get controls and models from there?

    // Return the last id generated
    def lastId: Int = {
      val idElement = staticStateElement.element(XFormsExtractor.LastIdQName)
      require(idElement ne null)
      idElement.idOrThrow.toInt
    }

    // Optional template as Base64
    def template: Option[String] = staticStateElement.elementOpt("template") map (_.getText)

    // Extract properties
    // NOTE: XFormsExtractor takes care of propagating only non-default properties
    val nonDefaultProperties: Map[String, (String, Boolean)] = {
      for {
        element       <- staticStateElement.elements(STATIC_STATE_PROPERTIES_QNAME)
        propertyName  = element.attributeValue("name")
        propertyValue = element.attributeValue("value")
        isInline      = element.attributeValue("inline") == true.toString
      } yield
        (propertyName, propertyValue -> isInline)
    } toMap

    val isHTMLDocument: Boolean =
      staticStateElement.attributeValueOpt("is-html") contains "true"

    def getOrComputeDigest(digest: Option[String]): String =
      digest getOrElse {
        val digestContentHandler = new DigestContentHandler
        TransformerUtils.writeDom4j(xmlDocument, digestContentHandler)
        NumberUtils.toHexString(digestContentHandler.getResult)
      }

    private def isClientStateHandling: Boolean = {
      def nonDefault = nonDefaultProperties.get(P.StateHandlingProperty) map (_._1 == StateHandlingClientValue)
      def default    = P.SupportedDocumentProperties(P.StateHandlingProperty).defaultValue.toString == StateHandlingClientValue

      nonDefault getOrElse default
    }

    // Get the encoded static state
    // If an existing state is passed in, use it, otherwise encode from XML, encrypting if necessary.
    // NOTE: We do compress the result as we think we can afford this for the static state (probably not so for the dynamic state).
    def asBase64 =
      EncodeDecode.encodeXML(xmlDocument, true, isClientStateHandling, true) // compress = true, encrypt = isClientStateHandling, location = true

    def dump() =
      println(xmlDocument.getRootElement.serializeToString(XMLWriter.PrettyFormat))
  }
}