/**
  * Copyright (C) 2018 Orbeon, Inc.
  *
  * This program is free software; you can redistribute it and/or modify it under the terms of the
  * GNU Lesser General Public License as published by the Free Software Foundation; either version
  *  2.1 of the License, or (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
  * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  * See the GNU Lesser General Public License for more details.
  *
  * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
  */
package org.orbeon.oxf.xforms.processor

import java.{lang => jl}
import io.circe.generic.auto._
import io.circe.syntax._
import org.orbeon.oxf.util.CoreCrossPlatformSupport
import org.orbeon.oxf.util.CoreUtils._
import org.orbeon.oxf.util.MarkupUtils._
import org.orbeon.oxf.util.StringUtils.StringOps
import org.orbeon.oxf.xforms.XFormsProperties._
import org.orbeon.oxf.xforms.XFormsGlobalProperties._
import org.orbeon.oxf.xforms.control.controls.XFormsRepeatControl
import org.orbeon.oxf.xforms.control.{Controls, XFormsComponentControl, XFormsControl, XFormsValueComponentControl}
import org.orbeon.oxf.xforms.event.XFormsEvents
import org.orbeon.oxf.xforms.{ShareableScript, XFormsContainingDocument}
import org.orbeon.xforms.{Constants, DeploymentType, EventNames, Message, ServerError, XFormsNames, rpc}

import scala.collection.mutable


object ScriptBuilder {

  // TODO: Constants duplicated from URLRewriterUtils
  val RESOURCES_VERSIONED_PROPERTY      = "oxf.resources.versioned"
  val RESOURCES_VERSION_NUMBER_PROPERTY = "oxf.resources.version-number"
  val RESOURCES_VERSIONED_DEFAULT       = false

  def quoteString(s: String) =
    s""""${s.escapeJavaScript}""""

  def escapeJavaScriptInsideScript (js: String): String = {
    // Method from https://stackoverflow.com/a/23983448/5295
    js.replace("</script", "</scr\\ipt")
  }

  def writeScripts(shareableScripts: Iterable[ShareableScript], write: String => Unit): Unit =
    for (shareableScript <- shareableScripts) {

      val paramsString =
        if (shareableScript.paramNames.nonEmpty)
          shareableScript.paramNames mkString (",", ",", "")
        else
          ""

      write(s"\nfunction ${shareableScript.clientName}(event$paramsString) {\n")
      write(shareableScript.body)
      write("}\n")
    }

  def gatherJavaScriptInitializations(startControl: XFormsControl, includeValue: Boolean): List[(String, Option[String])] = {

    val controlsToInitialize = mutable.ListBuffer[(String, Option[String])]()

    Controls.ControlsIterator(startControl, includeSelf = false, followVisible = true) foreach {
      case c: XFormsValueComponentControl =>
        if (c.isRelevant) {
          val abstractBinding = c.staticControl.commonBinding
          if (abstractBinding.modeJavaScriptLifecycle)
            controlsToInitialize +=
              c.getEffectiveId -> (
                if (includeValue && abstractBinding.modeExternalValue)
                  c.externalValueOpt
                else
                  None
              )
        }
      case c: XFormsComponentControl =>
        if (c.isRelevant && c.staticControl.commonBinding.modeJavaScriptLifecycle)
          controlsToInitialize += c.getEffectiveId -> None
      case c =>
        // Legacy JavaScript initialization
        // As of 2016-08-04: `xxf:dialog`, `xf:select1[appearance = compact]`, `xf:range`
        if (c.hasJavaScriptInitialization && ! c.isStaticReadonly)
          controlsToInitialize += c.getEffectiveId -> None
    }

    controlsToInitialize.result
  }

  private def findConfigurationProperties(
    containingDocument : XFormsContainingDocument,
    versionedResources : Boolean,
    heartbeatDelay     : Long
  ): Option[String] = {

    // Gather all static properties that need to be sent to the client
    val staticProperties = containingDocument.staticState.clientNonDefaultProperties

    val dynamicProperties = {

      def dynamicProperty[T](p: Boolean, name: String, value: T): Option[(String, T)] =
        p option (name -> value)

      // Heartbeat delay is dynamic because it depends on session duration
      def heartbeatOpt =
        Some(SessionHeartbeatDelayProperty -> heartbeatDelay)

      // Help events are dynamic because they depend on whether the xforms-help event is used
      // TODO: Better way to enable/disable xforms-help event support, maybe static analysis of event handlers?
      def helpOpt = dynamicProperty(
        containingDocument.staticOps.hasHandlerForEvent(XFormsEvents.XFORMS_HELP, includeAllEvents = false),
        HelpHandlerProperty,
        true
      )

      // Whether resources are versioned
      def resourcesVersionedOpt = dynamicProperty(
        versionedResources != RESOURCES_VERSIONED_DEFAULT,
        RESOURCES_VERSIONED_PROPERTY,
        versionedResources
      )

      // Application version is not an XForms property but we want to expose it on the client
      def resourcesVersionOpt =
        CoreCrossPlatformSupport.getApplicationResourceVersion flatMap
        (
          dynamicProperty(
            versionedResources,
            RESOURCES_VERSION_NUMBER_PROPERTY,
            _
          )
        )

      // Gather all dynamic properties that are defined
      List(heartbeatOpt, helpOpt, resourcesVersionedOpt, resourcesVersionOpt).flatten
    }

    val globalProperties = List(
      DelayBeforeAjaxTimeoutProperty -> getAjaxTimeout,
      RetryDelayIncrement            -> getRetryDelayIncrement,
      RetryMaxDelay                  -> getRetryMaxDelay
    )

    // combine all static and dynamic properties
    val clientProperties = staticProperties ++ dynamicProperties ++ globalProperties

    clientProperties.nonEmpty option {

      val sb = new jl.StringBuilder

      sb append "{"

      for (((propertyName, propertyValue), index) <- clientProperties.toList.zipWithIndex) {

        if (index != 0)
          sb append ','

        sb append '"'
        sb append propertyName
        sb append "\":"

        propertyValue match {
          case s: String =>
            sb append '"'
            sb append s
            sb append '"'
          case _ =>
            sb append propertyValue.toString
        }
      }

      sb append "}"

      sb.toString
    }
  }

  def buildJsonInitializationData(
    containingDocument   : XFormsContainingDocument,
    rewriteResource      : String => String,
    rewriteAction        : String => String,
    controlsToInitialize : List[(String, Option[String])],
    versionedResources   : Boolean,
    heartbeatDelay       : Long
  ): String = {

    val currentTime = System.currentTimeMillis

    val xformsSubmissionPathOpt =
      if (containingDocument.getDeploymentType != DeploymentType.Standalone || containingDocument.isPortletContainer || containingDocument.isEmbeddedFromHeaderOrUrlParam)
        Some(Constants.XFormsServerSubmit)
      else
        None

    rpc.Initializations(
      uuid                           = containingDocument.uuid,
      namespacedFormId               = containingDocument.getNamespacedFormId,
      repeatTree                     = containingDocument.staticOps.getRepeatHierarchyString(containingDocument.getContainerNamespace),
      repeatIndexes                  = XFormsRepeatControl.currentNamespacedIndexesString(containingDocument),
      xformsServerPath               = rewriteResource("/xforms-server"),
      xformsServerSubmitActionPath   = xformsSubmissionPathOpt.map(rewriteAction),
      xformsServerSubmitResourcePath = xformsSubmissionPathOpt.map(rewriteResource),
      xformsServerUploadPath         = rewriteResource("/xforms-server/upload"),
      controls  =
        for {
          (id, valueOpt) <- controlsToInitialize
        } yield
          rpc.Control(containingDocument.namespaceId(id), valueOpt),
      listeners =
        (
          for {
            handler  <- containingDocument.staticOps.keyboardHandlers
            observer <- handler.observersPrefixedIds
            keyText  <- handler.keyText
          } yield
            rpc.KeyListener(handler.eventNames filter EventNames.KeyboardEvents, observer, keyText, handler.keyModifiers)
        ).distinct,
      pollEvent =
        for {
          delayedEvent <- containingDocument.findEarliestPendingDelayedEvent
          time         <- delayedEvent.time
        } yield
          rpc.PollEvent(time - currentTime),
      userScripts =
        for (script <- containingDocument.getScriptsToRun.toList collect { case Right(s) => s })
        yield
          rpc.UserScript(
            functionName = script.script.shared.clientName,
            targetId     = containingDocument.namespaceId(script.targetEffectiveId),
            observerId   = containingDocument.namespaceId(script.observerEffectiveId),
            paramValues  = script.paramValues
          ),
      properties =
        findConfigurationProperties(containingDocument, versionedResources, heartbeatDelay),
      messagesToRun =
        (containingDocument.getMessagesToRun collect { case Message(text, "modal") => text }).toList,
      dialogsToShow =
        (
          for {
            dialogControl <- containingDocument.controls.getCurrentControlTree.getDialogControls
            if dialogControl.isDialogVisible
          } yield
            rpc.Dialog(
              id         = containingDocument.namespaceId(dialogControl.getEffectiveId),
              neighborId = dialogControl.neighborControlId.map(containingDocument.namespaceId)
            )
        ).toList,
      focusElementId =
        containingDocument.controls.getFocusedControl.map(c => containingDocument.namespaceId(c.getEffectiveId)),
      errorsToShow =
        containingDocument.getServerErrors.nonEmpty option
          rpc.Error(
            title   = "Non-fatal error",
            details = ServerError.errorsAsHtmlString(containingDocument.getServerErrors),
            formId  = containingDocument.getNamespacedFormId
          )
    ).asJson.noSpaces
  }

  def buildInitializationCall(jsonInitialization: String, contextPathOpt: Option[String], namespaceOpt: Option[String]): String = {
    val p1 = ScriptBuilder.quoteString(jsonInitialization)
    namespaceOpt match {
      case Some(namespace) =>
        val p2 = contextPathOpt.map(ScriptBuilder.quoteString).getOrElse("undefined")
        val p3 = ScriptBuilder.quoteString(namespace)
        s"""(function(){ORBEON.xforms.InitSupport.initializeFormWithInitData($p1,$p2,$p3)}).call(this);"""
      case None =>
        s"""(function(){ORBEON.xforms.InitSupport.initializeFormWithInitData($p1)}).call(this);"""
    }
  }

  private def findOtherScriptInvocations(containingDocument: XFormsContainingDocument): Option[String] = {

    val javascriptLoads =
      containingDocument.getScriptsToRun collect { case Left(l) => l.resource.substringAfter("javascript:") }

    javascriptLoads.nonEmpty option {

      val sb = new jl.StringBuilder

        sb append "\nfunction xformsPageLoadedServer() { "

        // NOTE: The order of script actions vs. `javascript:` loads should be preserved. It is not currently.

      // javascript: loads
      for (load <- javascriptLoads) {
        val body = load.escapeJavaScript
        sb append s"""(function(){$body})();"""
      }

      sb.toString
    }
  }
}