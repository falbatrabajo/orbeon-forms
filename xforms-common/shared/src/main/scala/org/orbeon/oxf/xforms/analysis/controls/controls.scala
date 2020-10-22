package org.orbeon.oxf.xforms.analysis.controls

import org.orbeon.dom.{Element, QName}
import org.orbeon.oxf.util.CoreUtils._
import org.orbeon.oxf.xforms.analysis.controls.ExternalEvents._
import org.orbeon.oxf.xforms.analysis.{ElementAnalysis, PartAnalysisImpl, WithChildrenTrait}
import org.orbeon.oxf.xforms.event.XFormsEvents._
import org.orbeon.oxf.xforms.itemset.Itemset
import org.orbeon.saxon.expr.StringLiteral
import org.orbeon.xforms.EventNames
import org.orbeon.xforms.XFormsNames._
import org.orbeon.xforms.xbl.Scope
import org.orbeon.xml.NamespaceMapping


private object ExternalEvents {

  val TriggerExternalEvents = Set(XFORMS_FOCUS, XXFORMS_BLUR, XFORMS_HELP, DOM_ACTIVATE)
  val ValueExternalEvents   = TriggerExternalEvents + EventNames.XXFormsValue

  // NOTE: `xxforms-upload-done` is a trusted server event so doesn't need to be listed here
  val UploadExternalEvents  = Set(
    XFORMS_SELECT,
    EventNames.XXFormsUploadStart,
    EventNames.XXFormsUploadProgress,
    EventNames.XXFormsUploadCancel,
    EventNames.XXFormsUploadError
  )
}

abstract class CoreControl(
  part             : PartAnalysisImpl,
  index            : Int,
  element          : Element,
  parent           : Option[ElementAnalysis],
  preceding        : Option[ElementAnalysis],
  staticId         : String,
  prefixedId       : String,
  namespaceMapping : NamespaceMapping,
  scope            : Scope,
  containerScope   : Scope
) extends ElementAnalysis(part, index, element, parent, preceding, staticId, prefixedId, namespaceMapping, scope, containerScope)
     with ViewTrait
     with StaticLHHASupport


abstract class ValueControl(
  part             : PartAnalysisImpl,
  index            : Int,
  element          : Element,
  parent           : Option[ElementAnalysis],
  preceding        : Option[ElementAnalysis],
  staticId         : String,
  prefixedId       : String,
  namespaceMapping : NamespaceMapping,
  scope            : Scope,
  containerScope   : Scope
) extends CoreControl(part, index, element, parent, preceding, staticId, prefixedId, namespaceMapping, scope, containerScope)
     with ValueTrait
     with WithChildrenTrait
     with FormatTrait

class InputValueControl(
  part             : PartAnalysisImpl,
  index            : Int,
  element          : Element,
  parent           : Option[ElementAnalysis],
  preceding        : Option[ElementAnalysis],
  staticId         : String,
  prefixedId       : String,
  namespaceMapping : NamespaceMapping,
  scope            : Scope,
  containerScope   : Scope
) extends ValueControl(part, index, element, parent, preceding, staticId, prefixedId, namespaceMapping, scope, containerScope)
     with RequiredSingleNode {
  override protected def externalEventsDef = super.externalEventsDef ++ ValueExternalEvents
  override val externalEvents = externalEventsDef
}

class SelectionControl(
  part                 : PartAnalysisImpl,
  index                : Int,
  element              : Element,
  parent               : Option[ElementAnalysis],
  preceding            : Option[ElementAnalysis],
  staticId             : String,
  prefixedId           : String,
  namespaceMapping     : NamespaceMapping,
  scope                : Scope,
  containerScope       : Scope,
  val staticItemset    : Option[Itemset],
  val useCopy          : Boolean,
  val mustEncodeValues : Option[Boolean]
) extends InputValueControl(part, index, element, parent, preceding, staticId, prefixedId, namespaceMapping, scope, containerScope)
     with SelectionControlTrait {

  def hasStaticItemset: Boolean = staticItemset.isDefined

  override protected val allowedExtensionAttributes = (! isMultiple && isFull set XXFORMS_GROUP_QNAME) + XXFORMS_TITLE_QNAME
}

class TriggerControl(
  part             : PartAnalysisImpl,
  index            : Int,
  element          : Element,
  parent           : Option[ElementAnalysis],
  preceding        : Option[ElementAnalysis],
  staticId         : String,
  prefixedId       : String,
  namespaceMapping : NamespaceMapping,
  scope            : Scope,
  containerScope   : Scope
) extends CoreControl(part, index, element, parent, preceding, staticId, prefixedId, namespaceMapping, scope, containerScope)
     with OptionalSingleNode
     with TriggerAppearanceTrait
     with WithChildrenTrait {
  override protected def externalEventsDef = super.externalEventsDef ++ TriggerExternalEvents
  override val externalEvents              = externalEventsDef

  override protected val allowedExtensionAttributes = appearances(XFORMS_MINIMAL_APPEARANCE_QNAME) set XXFORMS_TITLE_QNAME
}

class UploadControl(
  part             : PartAnalysisImpl,
  index            : Int,
  element          : Element,
  parent           : Option[ElementAnalysis],
  preceding        : Option[ElementAnalysis],
  staticId         : String,
  prefixedId       : String,
  namespaceMapping : NamespaceMapping,
  scope            : Scope,
  containerScope   : Scope
) extends InputValueControl(part, index, element, parent, preceding, staticId, prefixedId, namespaceMapping, scope, containerScope) {

  val multiple: Boolean = element.attributeValueOpt(XXFORMS_MULTIPLE_QNAME) contains "true"

  override protected def externalEventsDef = super.externalEventsDef ++ UploadExternalEvents
  override val externalEvents = externalEventsDef

  override protected val allowedExtensionAttributes = Set(ACCEPT_QNAME, MEDIATYPE_QNAME, XXFORMS_TITLE_QNAME)
}

class InputControl(
  part             : PartAnalysisImpl,
  index            : Int,
  element          : Element,
  parent           : Option[ElementAnalysis],
  preceding        : Option[ElementAnalysis],
  staticId         : String,
  prefixedId       : String,
  namespaceMapping : NamespaceMapping,
  scope            : Scope,
  containerScope   : Scope
) extends InputValueControl(part, index, element, parent, preceding, staticId, prefixedId, namespaceMapping, scope, containerScope) {
  override protected val allowedExtensionAttributes = Set(
    XXFORMS_SIZE_QNAME,
    XXFORMS_TITLE_QNAME,
    XXFORMS_MAXLENGTH_QNAME,
    XXFORMS_PATTERN_QNAME, // HTML 5 forms attribute
    XXFORMS_AUTOCOMPLETE_QNAME
  )
}

class SecretControl(
  part             : PartAnalysisImpl,
  index            : Int,
  element          : Element,
  parent           : Option[ElementAnalysis],
  preceding        : Option[ElementAnalysis],
  staticId         : String,
  prefixedId       : String,
  namespaceMapping : NamespaceMapping,
  scope            : Scope,
  containerScope   : Scope
) extends InputValueControl(part, index, element, parent, preceding, staticId, prefixedId, namespaceMapping, scope, containerScope) {
  override protected val allowedExtensionAttributes = Set(
    XXFORMS_SIZE_QNAME,
    XXFORMS_MAXLENGTH_QNAME,
    XXFORMS_AUTOCOMPLETE_QNAME
  )
}

class TextareaControl(
  part             : PartAnalysisImpl,
  index            : Int,
  element          : Element,
  parent           : Option[ElementAnalysis],
  preceding        : Option[ElementAnalysis],
  staticId         : String,
  prefixedId       : String,
  namespaceMapping : NamespaceMapping,
  scope            : Scope,
  containerScope   : Scope
) extends InputValueControl(part, index, element, parent, preceding, staticId, prefixedId, namespaceMapping, scope, containerScope) {
  override protected val allowedExtensionAttributes = Set(
    XXFORMS_MAXLENGTH_QNAME,
    XXFORMS_COLS_QNAME,
    XXFORMS_ROWS_QNAME
  )
}

class SwitchControl(
  part             : PartAnalysisImpl,
  index            : Int,
  element          : Element,
  parent           : Option[ElementAnalysis],
  preceding        : Option[ElementAnalysis],
  staticId         : String,
  prefixedId       : String,
  namespaceMapping : NamespaceMapping,
  scope            : Scope,
  containerScope   : Scope
) extends ContainerControl(part, index, element, parent, preceding, staticId, prefixedId, namespaceMapping, scope, containerScope)
     with OptionalSingleNode
     with StaticLHHASupport
     with AppearanceTrait {

  val caseref           = element.attributeValueOpt(CASEREF_QNAME)
  val hasFullUpdate     = element.attributeValueOpt(XXFORMS_UPDATE_QNAME).contains(XFORMS_FULL_UPDATE)

  lazy val caseControls = children collect { case c: CaseControl => c }
  lazy val caseIds      = caseControls map (_.staticId) toSet
}

class CaseControl(
  part                : PartAnalysisImpl,
  index               : Int,
  element             : Element,
  parent              : Option[ElementAnalysis],
  preceding           : Option[ElementAnalysis],
  staticId            : String,
  prefixedId          : String,
  namespaceMapping    : NamespaceMapping,
  scope               : Scope,
  containerScope      : Scope,
  val valueExpression : Option[String],
  val valueLiteral    : Option[String]
) extends ContainerControl(part, index, element, parent, preceding, staticId, prefixedId, namespaceMapping, scope, containerScope)
     with OptionalSingleNode
     with StaticLHHASupport {

  val selected: Option[String] = element.attributeValueOpt(SELECTED_QNAME)
}

class GroupControl(
  part             : PartAnalysisImpl,
  index            : Int,
  element          : Element,
  parent           : Option[ElementAnalysis],
  preceding        : Option[ElementAnalysis],
  staticId         : String,
  prefixedId       : String,
  namespaceMapping : NamespaceMapping,
  scope            : Scope,
  containerScope   : Scope
) extends ContainerControl(part, index, element, parent, preceding, staticId, prefixedId, namespaceMapping, scope, containerScope)
     with OptionalSingleNode
     with StaticLHHASupport {

  // Extension attributes depend on the name of the element
  override protected val allowedExtensionAttributes =
    elementQName match {
      case Some(elementQName) if Set("td", "th")(elementQName.localName) =>
        Set(QName("rowspan"), QName("colspan"))
      case _ =>
        Set.empty
    }

  override val externalEvents = super.externalEvents + DOM_ACTIVATE // allow DOMActivate
}

class DialogControl(
  part             : PartAnalysisImpl,
  index            : Int,
  element          : Element,
  parent           : Option[ElementAnalysis],
  preceding        : Option[ElementAnalysis],
  staticId         : String,
  prefixedId       : String,
  namespaceMapping : NamespaceMapping,
  scope            : Scope,
  containerScope   : Scope
) extends ContainerControl(part, index, element, parent, preceding, staticId, prefixedId, namespaceMapping, scope, containerScope)
   with OptionalSingleNode
   with StaticLHHASupport {

  override val externalEvents =
    super.externalEvents + XXFORMS_DIALOG_CLOSE // allow xxforms-dialog-close
}