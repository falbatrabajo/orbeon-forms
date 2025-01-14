/**
 * Copyright (C) 2015 Orbeon, Inc.
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
package org.orbeon.oxf.xforms.submission

import cats.data.NonEmptyList
import cats.syntax.option._
import org.orbeon.dom.{Document, Node}
import org.orbeon.oxf.json.Converter
import org.orbeon.oxf.util.CollectionUtils.InsertPosition
import org.orbeon.oxf.util.StaticXPath.{DocumentNodeInfoType, VirtualNodeType}
import org.orbeon.oxf.util.{ConnectionResult, ContentTypes, IndentedLogger, XPath}
import org.orbeon.oxf.xforms._
import org.orbeon.oxf.xforms.action.actions.{XFormsDeleteAction, XFormsInsertAction}
import org.orbeon.oxf.xforms.event.events.{ErrorType, XFormsSubmitErrorEvent}
import org.orbeon.oxf.xforms.model.{DataModel, InstanceCaching, InstanceDataOps, XFormsInstance}
import org.orbeon.oxf.xml.dom.LocationSAXContentHandler
import org.orbeon.saxon.om
import org.orbeon.xforms.XFormsCrossPlatformSupport


/**
  * Handle replace="instance".
  */
class InstanceReplacer(submission: XFormsModelSubmission, containingDocument: XFormsContainingDocument)
  extends Replacer {

  // Unwrapped document set by `deserialize()`
  private var _resultingDocumentOpt: Option[Document Either DocumentNodeInfoType] = None
  def resultingDocumentOpt: Option[Either[Document, DocumentNodeInfoType]] = _resultingDocumentOpt

  // For CacheableSubmission
  private var wrappedDocumentInfo: Option[DocumentNodeInfoType] = None
  private var instanceCaching: Option[InstanceCaching] = None

  // CacheableSubmission: set fully wrapped resulting document info and caching info
  def setCachedResult(wrappedDocumentInfo: DocumentNodeInfoType, instanceCaching: InstanceCaching): Unit = {
    this.wrappedDocumentInfo = Option(wrappedDocumentInfo)
    this.instanceCaching     = Option(instanceCaching)
  }

  def deserialize(
    cxr: ConnectionResult,
    p  : SubmissionParameters,
    p2 : SecondPassParameters
  ): Unit = {
    // Deserialize here so it can run in parallel
    val contentType = cxr.mediatypeOrDefault(ContentTypes.XmlContentType)
    val isJSON = ContentTypes.isJSONContentType(contentType)
    if (ContentTypes.isXMLContentType(contentType) || isJSON) {
      implicit val detailsLogger = submission.getDetailsLogger(p, p2)
      _resultingDocumentOpt = Some(
        deserializeInstance(
          isReadonly       = p2.isReadonly,
          isHandleXInclude = p2.isHandleXInclude,
          isJSON           = isJSON,
          connectionResult = cxr
        )
      )
    } else {
      // Other media type is not allowed
      throw new XFormsSubmissionException(
        submission       = submission,
        message          = s"""Body received with non-XML media type for `replace="instance"`: $contentType""",
        description      = "processing instance replacement",
        submitErrorEvent = new XFormsSubmitErrorEvent(
          submission,
          ErrorType.ResourceError,
          cxr.some
        )
      )
    }
  }

  private def deserializeInstance(
    isReadonly       : Boolean,
    isHandleXInclude : Boolean,
    isJSON           : Boolean,
    connectionResult : ConnectionResult)(implicit
    logger           : IndentedLogger
  ): Document Either DocumentNodeInfoType = {
    // Create resulting instance whether entire instance is replaced or not, because this:
    // 1. Wraps a Document within a DocumentInfo if needed
    // 2. Performs text nodes adjustments if needed
    try {
      ConnectionResult.withSuccessConnection(connectionResult, closeOnSuccess = true) { is =>

        if (! isReadonly) {
          if (logger.debugEnabled)
            logger.logDebug("", "deserializing to mutable instance")
          // Q: What about configuring validation? And what default to choose?

          Left(
            if (isJSON) {
              val receiver = new LocationSAXContentHandler
              Converter.jsonStringToXmlStream(SubmissionUtils.readTextContent(connectionResult.content).get, receiver)
              receiver.getDocument
            } else {
              XFormsCrossPlatformSupport.readOrbeonDom(is, connectionResult.url, isHandleXInclude, handleLexical = true)
            }
          )
        } else {
          if (logger.debugEnabled)
            logger.logDebug("", "deserializing to read-only instance")
          // Q: What about configuring validation? And what default to choose?
          // NOTE: isApplicationSharedHint is always false when get get here. `isApplicationSharedHint="true"` is handled above.

          Right(
            if (isJSON)
              Converter.jsonStringToXmlDoc(SubmissionUtils.readTextContent(connectionResult.content).get)
            else
              XFormsCrossPlatformSupport.readTinyTree(XPath.GlobalConfiguration, is, connectionResult.url, isHandleXInclude, handleLexical = true)
          )
        }
      }
    } catch {
      case e: Exception =>
        throw new XFormsSubmissionException(
          submission       = submission,
          message          = "xf:submission: exception while reading XML response.",
          description      = "processing instance replacement",
          throwable        = e,
          submitErrorEvent = new XFormsSubmitErrorEvent(
            submission,
            ErrorType.ParseError,
            connectionResult.some
          )
        )
    }
  }

  def replace(
    cxr: ConnectionResult,
    p  : SubmissionParameters,
    p2 : SecondPassParameters
  ): ReplaceResult =
    submission.findReplaceInstanceNoTargetref(p.refContext.refInstanceOpt) match {
      case None =>

        // Replacement instance or node was specified but not found
        //
        // Not sure what's the right thing to do with 1.1, but this could be done
        // as part of the model's static analysis if the instance value is not
        // obtained through AVT, and dynamically otherwise.
        //
        // Another option would be to dispatch, at runtime, an xxforms-binding-error event. xforms-submit-error is
        // consistent with targetref, so might be better.

        ReplaceResult.SendError(
          new XFormsSubmissionException(
            submission       = submission,
            message          = """`instance` attribute doesn't point to an existing instance for `replace="instance"`.""",
            description      = "processing `instance` attribute",
            submitErrorEvent = new XFormsSubmitErrorEvent(
              target    = submission,
              errorType = ErrorType.TargetError,
              cxrOpt    = cxr.some
            )
          ),
          Left(cxr.some)
        )

      case Some(replaceInstanceNoTargetref) =>

        val destinationNodeInfoOpt =
          submission.evaluateTargetRef(
            p.refContext.xpathContext,
            replaceInstanceNoTargetref,
            p.refContext.submissionElementContextItem
          )

        destinationNodeInfoOpt match {
          case None =>
            // XForms 1.1: "If the processing of the `targetref` attribute fails,
            // then submission processing ends after dispatching the event
            // `xforms-submit-error` with an `error-type` of `target-error`."
            ReplaceResult.SendError(
              new XFormsSubmissionException(
                submission       = submission,
                message          = """targetref attribute doesn't point to an element for `replace="instance"`.""",
                description      = "processing targetref attribute",
                submitErrorEvent = new XFormsSubmitErrorEvent(
                  target    = submission,
                  errorType = ErrorType.TargetError,
                  cxrOpt    = cxr.some
                )
              ),
              Left(cxr.some)
            )
          case Some(destinationNodeInfo) =>
            // This is the instance which is effectively going to be updated
            containingDocument.instanceForNodeOpt(destinationNodeInfo) match {
              case None =>
                ReplaceResult.SendError(
                  new XFormsSubmissionException(
                    submission       = submission,
                    message          = """targetref attribute doesn't point to an element in an existing instance for `replace="instance"`.""",
                    description      = "processing targetref attribute",
                    submitErrorEvent = new XFormsSubmitErrorEvent(
                      target    = submission,
                      errorType = ErrorType.TargetError,
                      cxrOpt    = cxr.some
                    )
                  ),
                  Left(cxr.some)
                )
              case Some(instanceToUpdate) =>
                // Whether the destination node is the root element of an instance
                val isDestinationRootElement = instanceToUpdate.rootElement.isSameNodeInfo(destinationNodeInfo)
                if (p2.isReadonly && ! isDestinationRootElement) {
                  // Only support replacing the root element of an instance when using a shared instance
                  ReplaceResult.SendError(
                    new XFormsSubmissionException(
                      submission       = submission,
                      message          = "targetref attribute must point to instance root element when using read-only instance replacement.",
                      description      = "processing targetref attribute",
                      submitErrorEvent = new XFormsSubmitErrorEvent(
                        target    = submission,
                        errorType = ErrorType.TargetError,
                        cxrOpt    = cxr.some
                      )
                    ),
                    Left(cxr.some)
                  )
                } else {
                  implicit val detailsLogger  = submission.getDetailsLogger(p, p2)

                  // Obtain root element to insert
                  if (detailsLogger.debugEnabled)
                    detailsLogger.logDebug(
                      "",
                      if (p2.isReadonly)
                        "replacing instance with read-only instance"
                      else
                        "replacing instance with mutable instance",
                      "instance",
                      instanceToUpdate.getEffectiveId
                    )

                  // Perform insert/delete. This will dispatch xforms-insert/xforms-delete events.
                  // "the replacement is performed by an XForms action that performs some
                  // combination of node insertion and deletion operations that are
                  // performed by the insert action (10.3 The insert Element) and the
                  // delete action"

                  // NOTE: As of 2009-03-18 decision, XForms 1.1 specifies that deferred event handling flags are set instead of
                  // performing RRRR directly.
                  val newDocumentInfo =
                    wrappedDocumentInfo getOrElse
                      XFormsInstance.createDocumentInfo(
                        _resultingDocumentOpt.get,
                        instanceToUpdate.instance.exposeXPathTypes
                      )

                  val applyDefaults = p2.applyDefaults
                  if (isDestinationRootElement) {
                    // Optimized insertion for instance root element replacement
                    if (applyDefaults)
                      newDocumentInfo match {
                        case node: VirtualNodeType =>
                          InstanceDataOps.setRequireDefaultValueRecursively(node.getUnderlyingNode.asInstanceOf[Node])
                        case _ => ()
                      }

                    instanceToUpdate.replace(
                      newDocumentInfo = newDocumentInfo,
                      dispatch        = true,
                      instanceCaching = instanceCaching,
                      isReadonly      = p2.isReadonly,
                      applyDefaults   = applyDefaults
                    )
                  } else {
                    // Generic insertion
                    instanceToUpdate.markModified()

                    // Perform the insertion

                    // Insert before the target node, so that the position of the inserted node
                    // wrt its parent does not change after the target node is removed
                    // This will also mark a structural change
                    // FIXME: Replace logic should use `doReplace` and `xxforms-replace` event
                    XFormsInsertAction.doInsert(
                      containingDocumentOpt             = containingDocument.some,
                      insertPosition                    = InsertPosition.Before,
                      insertLocation                    = Left(NonEmptyList(destinationNodeInfo, Nil) -> 1),
                      originItemsOpt                    = List(DataModel.firstChildElement(newDocumentInfo)).some,
                      doClone                           = false,
                      doDispatch                        = true,
                      requireDefaultValues              = applyDefaults,
                      searchForInstance                 = true,
                      removeInstanceDataFromClonedNodes = true,
                      structuralDependencies            = true
                    )

                    // Perform the deletion of the selected node
                    XFormsDeleteAction.doDeleteOne(
                      containingDocument = containingDocument,
                      nodeInfo           = destinationNodeInfo,
                      doDispatch         = true
                    )

                    // Update model instance
                    // NOTE: The inserted node NodeWrapper.index might be out of date at this point because:
                    // - doInsert() dispatches an event which might itself change the instance
                    // - doDelete() does as well
                    // Does this mean that we should check that the node is still where it should be?
                  }
                  ReplaceResult.SendDone(cxr)
                }
            }
        }
    }
}