package org.orbeon.oxf.xforms.analysis.controls

import cats.syntax.option._
import org.orbeon.datatypes.ExtendedLocationData
import org.orbeon.dom.saxon.DocumentWrapper
import org.orbeon.dom.{Element, QName, Text}
import org.orbeon.oxf.common.ValidationException
import org.orbeon.oxf.util.CoreUtils._
import org.orbeon.oxf.util.StringUtils._
import org.orbeon.oxf.util.{StaticXPath, XPath, XPathCache}
import org.orbeon.oxf.xforms.XFormsElementValue
import org.orbeon.oxf.xforms.XFormsStaticStateImpl
import org.orbeon.oxf.xforms.analysis.ElementAnalysis.attQNameSet
import org.orbeon.oxf.xforms.analysis.controls.LHHAAnalysis.isHTML
import org.orbeon.oxf.xforms.analysis.{ElementAnalysis, ElementAnalysisTreeBuilder, PartAnalysisImpl}
import org.orbeon.oxf.xforms.itemset.{Item, ItemContainer, Itemset, LHHAValue}
import org.orbeon.oxf.xml.dom.Extensions.{DomElemOps, VisitorListener}
import org.orbeon.saxon.expr.StringLiteral
import org.orbeon.saxon.om
import org.orbeon.scaxon.SimplePath._
import org.orbeon.xforms.XFormsNames
import org.orbeon.xforms.XFormsNames._
import org.orbeon.xforms.xbl.Scope
import org.orbeon.xml.NamespaceMapping

import scala.annotation.tailrec

object OutputControlBuilder {

  def apply(
    part            : PartAnalysisImpl,
    index           : Int,
    element         : Element,
    parent          : Option[ElementAnalysis],
    preceding       : Option[ElementAnalysis],
    scope           : Scope
  ): OutputControl = {

    // TODO: This All this could be passed at construction
    val staticId  : String = element.idOrNull
    val prefixedId: String = scope.prefixedIdForStaticId(staticId) // NOTE: we could also pass the prefixed id during construction
    val containerScope: Scope = part.containingScope(prefixedId)
    val namespaceMapping: NamespaceMapping = part.metadata.getNamespaceMapping(prefixedId).orNull

    // TODO: Duplication in trait
    val appearances: Set[QName]     = ElementAnalysis.attQNameSet(element, APPEARANCE_QNAME, namespaceMapping)

    // Control-specific
    val isImageMediatype    : Boolean = element.attributeValueOpt("mediatype") exists (_.startsWith("image/"))
    val isHtmlMediatype     : Boolean = element.attributeValueOpt("mediatype") contains "text/html"
    val isDownloadAppearance: Boolean = appearances.contains(XXFORMS_DOWNLOAD_APPEARANCE_QNAME)

    val staticValue: Option[String] =
      (! isImageMediatype && ! isDownloadAppearance && ElementAnalysisTreeBuilder.hasStaticValue(element)) option
        XFormsElementValue.getStaticChildElementValue(containerScope.fullPrefix, element, acceptHTML = true, null)

    new OutputControl(
      part,
      index,
      element,
      parent,
      preceding,
      scope,
      isImageMediatype,
      isHtmlMediatype,
      isDownloadAppearance,
      staticValue
    )
  }
}

object LHHAAnalysisBuilder {

  // TODO: Duplicatino from `XFormsProperties`
  val LabelAppearanceProperty = "label.appearance"
  val HintAppearanceProperty  = "hint.appearance"
  val HelpAppearanceProperty  = "help.appearance"

  def apply(
    part            : PartAnalysisImpl,
    index           : Int,
    element         : Element,
    parent          : Option[ElementAnalysis],
    preceding       : Option[ElementAnalysis],
    scope           : Scope
  ): LHHAAnalysis = {

    // TODO: This All this could be passed at construction
    val staticId  : String = element.idOrNull
    val prefixedId: String = scope.prefixedIdForStaticId(staticId) // NOTE: we could also pass the prefixed id during construction
    val containerScope: Scope = part.containingScope(prefixedId)
    val namespaceMapping: NamespaceMapping = part.metadata.getNamespaceMapping(prefixedId).orNull

    // TODO: Duplication in trait
    val appearances: Set[QName]     = ElementAnalysis.attQNameSet(element, APPEARANCE_QNAME, namespaceMapping)

    // TODO: make use of static value
    //
    // - output static value in HTML markup
    // - if has static value, don't attempt to compare values upon diff, and never send new related information to client
    // - 2017-10-17: Now using this in `XFormsLHHAControl`.
    //
    // TODO: figure out whether to allow HTML or not (could default to true?)
    //
    val staticValue: Option[String] =
      ElementAnalysisTreeBuilder.hasStaticValue(element) option
        XFormsElementValue.getStaticChildElementValue(containerScope.fullPrefix, element, acceptHTML = true, null)

    val lhhaType: LHHA =
      LHHA.withNameOption(element.getName) getOrElse
        LHHA.Label // FIXME: Because `SelectionControlTrait` calls this for `value`!

    val hasLocalMinimalAppearance = appearances(XFORMS_MINIMAL_APPEARANCE_QNAME) || appearances(XXFORMS_PLACEHOLDER_APPEARANCE_QNAME)
    val hasLocalFullAppearance    = appearances(XFORMS_FULL_APPEARANCE_QNAME)
    val hasLocalLeftAppearance    = appearances(XXFORMS_LEFT_APPEARANCE_QNAME)

    // Placeholder is only supported for label or hint. This in fact only makes sense for a limited set
    // of controls, namely text fields or text areas at this point.
    val isPlaceholder: Boolean =
      lhhaType match {
        case LHHA.Label | LHHA.Hint =>
          hasLocalMinimalAppearance || (
            ! hasLocalFullAppearance &&
              part.staticState.staticStringProperty(
                if (lhhaType == LHHA.Hint) HintAppearanceProperty else LabelAppearanceProperty
              )
            .tokenizeToSet.contains(XFORMS_MINIMAL_APPEARANCE_QNAME.localName)
          )
        case _ => false
      }

    new LHHAAnalysis(
      part,
      index,
      element,
      parent,
      preceding,
      scope,
      staticValue,
      isPlaceholder,
      containsHTML(element),
      hasLocalMinimalAppearance,
      hasLocalFullAppearance,
      hasLocalLeftAppearance
    )
  }

  // Attach this LHHA to its target control if any
  def attachToControl(part: PartAnalysisImpl, lhhaAnalysis: LHHAAnalysis): Unit = {

    val (targetControl, effectiveTargetControlOrPrefixedIdOpt) = {

      def searchLHHAControlInScope(scope: Scope, forStaticId: String): Option[StaticLHHASupport] =
        part.findControlAnalysis(scope.prefixedIdForStaticId(forStaticId)) collect { case e: StaticLHHASupport => e}

      @tailrec
      def searchXblLabelFor(e: StaticLHHASupport): Option[StaticLHHASupport Either String] =
        e match {
          case xbl: ComponentControl =>
            xbl.commonBinding.labelFor match {
              case Some(nestedLabelForStaticId) =>
                searchLHHAControlInScope(xbl.bindingOrThrow.innerScope, nestedLabelForStaticId) match {
                  case Some(nestedLabelForTarget) => searchXblLabelFor(nestedLabelForTarget) // recurse
                  case None                       => Some(Right(xbl.bindingOrThrow.innerScope.fullPrefix + nestedLabelForStaticId)) // assuming id of an HTML element
                }
              case None =>
                Some(Left(xbl))
            }
          case _ =>
            Some(Left(e))
        }

      def initialElemFromForOpt =
        lhhaAnalysis.forStaticIdOpt map  { forStaticId =>
          searchLHHAControlInScope(lhhaAnalysis.scope, forStaticId) getOrElse (
            throw new ValidationException(
              s"`for` attribute with value `$forStaticId` doesn't point to a control supporting label, help, hint or alert.",
              ElementAnalysis.createLocationData(lhhaAnalysis.element)
            )
          )
        }

      val initialElem = initialElemFromForOpt getOrElse {
        lhhaAnalysis.getParent match {
          case e: StaticLHHASupport => e
          case _ =>
            throw new ValidationException(
              s"parent control must support label, help, hint or alert.",
              ElementAnalysis.createLocationData(lhhaAnalysis.element)
            )
        }
      }

      (initialElem, searchXblLabelFor(initialElem))
    }

    // NOTE: We don't support a reference to an effective control within an XBL which is in a repeat nested within the XBL!
    val repeatNesting = targetControl.ancestorRepeats.size - lhhaAnalysis.ancestorRepeats.size

    lhhaAnalysis._isForRepeat                           = ! lhhaAnalysis.isLocal && repeatNesting > 0
    lhhaAnalysis._forRepeatNesting                      = if (lhhaAnalysis._isForRepeat && repeatNesting > 0) repeatNesting else 0
    lhhaAnalysis._directTargetControlOpt                = Some(targetControl)
    lhhaAnalysis._effectiveTargetControlOrPrefixedIdOpt = effectiveTargetControlOrPrefixedIdOpt

    // We attach the LHHA to one, and possibly two target controls
    targetControl.attachLHHA(lhhaAnalysis)
    effectiveTargetControlOrPrefixedIdOpt foreach {
      _.left.toOption filter (_ ne targetControl) foreach (_.attachLHHABy(lhhaAnalysis))
    }
  }

  private def containsHTML(lhhaElement: Element) = {

    val lhhaElem =
      new DocumentWrapper(
          lhhaElement.getDocument,
          null,
          XPath.GlobalConfiguration
        ).wrap(lhhaElement)

    val XFOutput = URIQualifiedName(XFORMS_NAMESPACE_URI, "output")

    val descendantOtherElems = lhhaElem descendant * filter (_.uriQualifiedName != XFOutput)
    val descendantOutputs    = lhhaElem descendant XFOutput

    isHTML(lhhaElement) || descendantOtherElems.nonEmpty || (descendantOutputs exists {
      _.attValueOpt("mediatype") contains "text/html"
    })
  }
}

object SelectionControlBuilder {

  def apply(
    part            : PartAnalysisImpl,
    index           : Int,
    element         : Element,
    parent          : Option[ElementAnalysis],
    preceding       : Option[ElementAnalysis],
    scope           : Scope
  ): SelectionControl = {

    val locationData = ElementAnalysis.createLocationData(element)

    // Try to figure out if we have dynamic items. This attempts to cover all cases, including
    // nested xf:output controls. Check only under xf:choices, xf:item and xf:itemset so that we
    // don't check things like event handlers. Also check for AVTs.
    val hasStaticItemset: Boolean =
      ! XPathCache.evaluateSingle(
        contextItem = newElemWrapper(element),
        xpathString =
          """
          exists(
            (xf:choices | xf:item | xf:itemset)/
            descendant-or-self::*[
              @ref     or
              @nodeset or
              @bind    or
              @value   or
              @*[
                contains(., '{')
              ]
            ]
          )
        """,
        namespaceMapping   = XFormsStaticStateImpl.BASIC_NAMESPACE_MAPPING,
        variableToValueMap = null,
        functionLibrary    = null,
        functionContext    = null,
        baseURI            = null,
        locationData       = locationData,
        reporter           = null
      ).asInstanceOf[Boolean]

    // TODO: This All this could be passed at construction
    val staticId  : String = element.idOrNull
    val prefixedId: String = scope.prefixedIdForStaticId(staticId) // NOTE: we could also pass the prefixed id during construction
    val containerScope: Scope = part.containingScope(prefixedId)
    val namespaceMapping: NamespaceMapping = part.metadata.getNamespaceMapping(prefixedId).orNull

    val isMultiple = element.getName == "select"

    // TODO: Duplication in trait
    val appearances = {

      // Ignore no longer supported `xxf:autocomplete` (which would require `selection="open"` anyway)
      val initialAppearances =
        ElementAnalysis.attQNameSet(element, XFormsNames.APPEARANCE_QNAME, namespaceMapping) -
          XXFORMS_AUTOCOMPLETE_APPEARANCE_QNAME

      if (isMultiple && initialAppearances(XFORMS_MINIMAL_APPEARANCE_QNAME))
        // Select with minimal appearance is handled as a compact appearance
        initialAppearances - XFORMS_MINIMAL_APPEARANCE_QNAME + XFORMS_COMPACT_APPEARANCE_QNAME
      else if (initialAppearances.nonEmpty)
        initialAppearances
      else if (isMultiple)
        Set(XFORMS_COMPACT_APPEARANCE_QNAME) // default for xf:select
      else
        Set(XFORMS_MINIMAL_APPEARANCE_QNAME) // default for xf:select1
    }

    val isFull    = appearances(XFORMS_FULL_APPEARANCE_QNAME)
    val isCompact = appearances(XFORMS_COMPACT_APPEARANCE_QNAME)

    // Return the control's static itemset if any
    val staticItemset: Option[Itemset] =
      hasStaticItemset option evaluateStaticItemset(element, isMultiple, isFull, containerScope)

    val useCopy: Boolean = {

      val wrapper = newElemWrapper(element)

      val hasCopyElem  = wrapper descendant XFORMS_COPY_QNAME  nonEmpty
      val hasValueElem = wrapper descendant XFORMS_VALUE_QNAME nonEmpty

      // This limitation could be lifted in the future
      if (hasValueElem && hasCopyElem)
        throw new ValidationException(
          s"an itemset cannot have both `xf:copy` and `xf:value` elements",
          ElementAnalysis.createLocationData(element)
        )

      hasCopyElem
    }

    val mustEncodeValues: Option[Boolean] =
      if (useCopy)
        true.some
      else
        element.attributeValueOpt(ENCRYPT_ITEM_VALUES) map (_.toBoolean)

    new SelectionControl(
      part,
      index,
      element,
      parent,
      preceding,
      scope,
      staticItemset,
      useCopy,
      mustEncodeValues
    )
  }

  private def newElemWrapper(element: Element): om.NodeInfo = new DocumentWrapper(
    element.getDocument,
    null,
    XPath.GlobalConfiguration
  ).wrap(element)

  private def evaluateStaticItemset(element: Element, isMultiple: Boolean, isFull: Boolean, containerScope: Scope) = {

    // TODO: operate on nested ElementAnalysis instead of Element

    val result = new Itemset(isMultiple, hasCopy = false)

    element.visitDescendants(
      new VisitorListener {

        private var position = 0
        private var currentContainer: ItemContainer = result

        def startElement(element: Element): Unit = {

          def findLhhValue(qName: QName, required: Boolean): Option[LHHAValue] = {

            element.elementOpt(qName) match {
              case Some(lhhaElem) =>

                val containsHTML = Array[Boolean](false)

                val valueOpt =
                  XFormsElementValue.getStaticChildElementValue(
                    containerScope.fullPrefix,
                    lhhaElem,
                    isFull,
                    containsHTML
                  ).trimAllToOpt

                if (required)
                  LHHAValue(valueOpt getOrElse "", containsHTML(0)).some
                else
                  valueOpt map (LHHAValue(_, containsHTML(0)))

              case None =>
                if (required)
                  throw new ValidationException(
                    "`xf:item` or `xf:itemset` must contain an `xf:label` element",
                    ElementAnalysis.createLocationData(element)
                  )
                else
                  None
            }
          }

          element.getQName match {

            case XFORMS_ITEM_QNAME => // xf:item

              val labelOpt = findLhhValue(LABEL_QNAME, required = true)
              val helpOpt  = findLhhValue(HELP_QNAME,  required = false)
              val hintOpt  = findLhhValue(HINT_QNAME,  required = false)

              val valueOpt = {

                val rawValue =
                  element.elementOpt(XFORMS_VALUE_QNAME) map (
                    XFormsElementValue.getStaticChildElementValue(
                      containerScope.fullPrefix,
                      _,
                      acceptHTML = false,
                      null
                    )
                  ) getOrElse (
                    throw new ValidationException(
                      "xf:item must contain an xf:value element.",
                      ElementAnalysis.createLocationData(element)
                    )
                  )

                if (isMultiple)
                  rawValue.trimAllToOpt
                else
                  rawValue.some
              }

              valueOpt foreach { value =>
                currentContainer.addChildItem(
                  Item.ValueNode(
                    label      = labelOpt getOrElse LHHAValue.Empty,
                    help       = helpOpt,
                    hint       = hintOpt,
                    value      = Left(value),
                    attributes = SelectionControlUtil.getAttributes(element)
                  )(
                    position   = position
                  )
                )
                position += 1
              }

            case XFORMS_ITEMSET_QNAME => // xf:itemset

              throw new ValidationException(
                "xf:itemset must not appear in static itemset.",
                ElementAnalysis.createLocationData(element)
              )

            case XFORMS_CHOICES_QNAME => // xf:choices

              val labelOpt = findLhhValue(LABEL_QNAME, required = false)

              labelOpt foreach { _ =>
                val newContainer = Item.ChoiceNode(
                  label      = labelOpt getOrElse LHHAValue.Empty,
                  attributes = SelectionControlUtil.getAttributes(element)
                )(
                  position   = position
                )
                position += 1
                currentContainer.addChildItem(newContainer)
                currentContainer = newContainer
              }

            case _ => // ignore
          }
        }

        def endElement(element: Element): Unit =
          if (element.getQName == XFORMS_CHOICES_QNAME) {
            if (element.elementOpt(LABEL_QNAME).isDefined)
              currentContainer = currentContainer.parent
          }

        def text(text: Text): Unit = ()
      },
      mutable = false
    )
    result
  }
}

object CaseControlBuilder {

  def apply(
    part      : PartAnalysisImpl,
    index     : Int,
    element   : Element,
    parent    : Option[ElementAnalysis],
    preceding : Option[ElementAnalysis],
    scope     : Scope
  ): CaseControl = {

    // TODO: This All this could be passed at construction
    val staticId  : String = element.idOrNull
    val prefixedId: String = scope.prefixedIdForStaticId(staticId) // NOTE: we could also pass the prefixed id during construction
    val containerScope: Scope = part.containingScope(prefixedId)
    val namespaceMapping: NamespaceMapping = part.metadata.getNamespaceMapping(prefixedId).orNull
    val locationData: ExtendedLocationData = ElementAnalysis.createLocationData(element)

    val valueExpression =
      element.attributeValueOpt(VALUE_QNAME)

    val valueLiteral =
      valueExpression flatMap { valueExpr =>

        val literal =
          XPath.evaluateAsLiteralIfPossible(
            xpathString      = StaticXPath.makeStringExpression(valueExpr),
            namespaceMapping = namespaceMapping,
            locationData     = locationData,
            functionLibrary  = part.staticState.functionLibrary,
            avt              = false)(
            logger           = null
          )

        literal collect {
          case literal: StringLiteral => literal.getStringValue
        }
      }

    new CaseControl(
      part,
      index,
      element,
      parent,
      preceding,
      scope,
      valueExpression,
      valueLiteral
    )
  }

}