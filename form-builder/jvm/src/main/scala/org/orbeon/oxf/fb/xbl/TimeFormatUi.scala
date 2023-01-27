package org.orbeon.oxf.fb.xbl

import org.orbeon.date.{IsoTime, TimeFormat}
import org.orbeon.oxf.fr.ui.ScalaToXml
import org.orbeon.saxon.om.{DocumentInfo, NodeInfo}


object TimeFormatUi extends ScalaToXml {

  type MyState = TimeFormat

  import io.circe.generic.auto._

  //@XPathFunction
  def timeFormatToXml(timeFormat: String): DocumentInfo =
    fullXmlToSimplifiedXml(stateToFullXml(IsoTime.parseFormat(timeFormat)))

  //@XPathFunction
  def xmlFormatToFormatString(timeFormatRootElem: NodeInfo): String =
    simplifiedXmlToState[TimeFormat](timeFormatRootElem).map(IsoTime.generateFormat).toOption.orNull
}