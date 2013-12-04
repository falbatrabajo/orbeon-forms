/**
 * Copyright (C) 2010 Orbeon, Inc.
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
package org.orbeon.oxf.xforms.model

import collection.JavaConverters._
import collection.{mutable ⇒ m}
import java.{util ⇒ ju}
import org.orbeon.oxf.xforms.XFormsModelBinds.BindRunner
import org.orbeon.oxf.xforms._
import org.orbeon.oxf.xforms.analysis.model.StaticBind
import org.orbeon.saxon.om.Item

class RuntimeBind(val model: XFormsModel, val staticBind: StaticBind, val parentIteration: BindIteration, isSingleNodeContext: Boolean) extends XFormsObject {

    def containingDocument = model.containingDocument
    def getEffectiveId     = XFormsUtils.getRelatedEffectiveId(model.getEffectiveId, staticId)
    def staticId           = staticBind.staticId

    val (items, bindNodes) = {
        val contextStack = model.getContextStack
        contextStack.pushBinding(staticBind.element, model.getEffectiveId, model.getResolutionScope)

        // NOTE: This should probably go into XFormsContextStack
        val bindingContext = contextStack.getCurrentBindingContext
        val items: ju.List[Item] =
            if (bindingContext.newBind)
                // Case where a @ref attribute is present -> a current nodeset is therefore available
                bindingContext.nodeset
            else {
                // Case where of missing @ref attribute (it is optional in XForms 1.1 and defaults to the context item)
                val contextItem = bindingContext.contextItem
                if (contextItem eq null) ju.Collections.emptyList() else ju.Collections.singletonList(contextItem)
            }

        assert(items ne null)

        val binds = model.getBinds

        // "4.7.2 References to Elements within a bind Element [...] If a target bind element is outermost, or if
        // all of its ancestor bind elements have nodeset attributes that select only one node, then the target bind
        // only has one associated bind object, so this is the desired target bind object whose nodeset is used in
        // the Single Node Binding or Node Set Binding"
        if (isSingleNodeContext)
            binds.singleNodeContextBinds += staticBind.staticId → this

        val itemsAsScala = items.asScala
        val itemsSize    = itemsAsScala.size

        val bindNodes: Seq[BindNode] =
            if (itemsAsScala.nonEmpty) {
                // Only then does it make sense to create BindNodes
                val childrenStaticBinds = staticBind.children
                if (childrenStaticBinds.size > 0) {
                    // There are children binds (and maybe MIPs)
                    val result = new m.ArrayBuffer[BindNode](itemsSize)
                    
                    val childrenBindsHaveSingleNodeContext = isSingleNodeContext && itemsSize == 1

                    // Iterate over nodeset and produce child iterations
                    var currentPosition = 1
                    for (item ← itemsAsScala) {
                        contextStack.pushIteration(currentPosition)

                        // Create iteration and remember it
                        val currentBindIteration = new BindIteration(this, currentPosition, item, childrenBindsHaveSingleNodeContext, childrenStaticBinds)
                        result += currentBindIteration

                        // Create mapping context item → iteration
                        // There might already be a mapping
                        if (! childrenBindsHaveSingleNodeContext) {
                            val existingIterations = binds.iterationsForContextItem.getOrElseUpdate(item, Nil)
                            binds.iterationsForContextItem += item → (currentBindIteration :: existingIterations)
                        }

                        contextStack.popBinding
                        currentPosition += 1
                    }
                    result
                } else if (staticBind.hasMIPs) {
                    // No children binds, but we have MIPs, so create holders too
                    val result = new m.ArrayBuffer[BindNode](itemsSize)
                    var currentPosition = 1
                    for (item ← itemsAsScala) {
                        result += new BindNode(this, currentPosition, item)
                        currentPosition += 1
                    }
                    result
                } else
                    Nil
            } else
                Nil

        contextStack.popBinding

        (items, bindNodes)
    }

    def applyBinds(bindRunner: BindRunner): Unit =
        if (bindNodes.nonEmpty) {
            for (bindNode ← bindNodes) {
                // Handle current node
                bindRunner.applyBind(bindNode)

                // Handle children binds if any
                bindNode match {
                    case iteration: BindIteration ⇒ iteration.applyBinds(bindRunner)
                    case _ ⇒
                }
            }
        }

    def getBindNode(position: Int) =
        bindNodes(position - 1)
}