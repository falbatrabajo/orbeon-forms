package org.orbeon.oxf.xforms

import cats.syntax.option._
import io.circe.generic.semiauto._
import io.circe.parser.decode
import io.circe.{Decoder, HCursor}
import org.orbeon.datatypes.MaximumSize
import org.orbeon.dom
import org.orbeon.dom.QName
import org.orbeon.oxf.http.BasicCredentials
import org.orbeon.oxf.util.CoreUtils._
import org.orbeon.oxf.util.{IndentedLogger, Modifier, StaticXPath}
import org.orbeon.oxf.xforms.analysis._
import org.orbeon.oxf.xforms.analysis.controls.SelectionControlUtil.TopLevelItemsetQNames
import org.orbeon.oxf.xforms.analysis.controls._
import org.orbeon.oxf.xforms.analysis.model._
import org.orbeon.oxf.xforms.itemset.{Item, Itemset, LHHAValue}
import org.orbeon.oxf.xforms.library.{XFormsFunctionLibrary, XXFormsFunctionLibrary}
import org.orbeon.oxf.xforms.state.AnnotatedTemplate
import org.orbeon.oxf.xforms.xbl.XBLAssets
import org.orbeon.oxf.xml.SAXStore
import org.orbeon.oxf.xml.dom.Extensions._
import org.orbeon.saxon.functions.{FunctionLibrary, FunctionLibraryList}
import org.orbeon.saxon.om
import org.orbeon.xforms.analysis.model.ValidationLevel
import org.orbeon.xforms.analysis.{Perform, Propagate}
import org.orbeon.xforms.xbl.Scope
import org.orbeon.xml.NamespaceMapping

import java.{util => ju}
import scala.collection.mutable


object XFormsStaticStateDeserializer {

  def deserialize(jsonString: String)(implicit logger: IndentedLogger): XFormsStaticState = {

    var collectedNamespaces = IndexedSeq[Map[String, String]]()

    val scopes = mutable.Map[String, Scope]()

    var controlStack: List[ElementAnalysis] = Nil

    // TODO: We'll need a mechanism to plug the Form Runner function library.
    val functionLibrary: FunctionLibrary =
      new FunctionLibraryList |!>
        (_.addFunctionLibrary(XFormsFunctionLibrary)) |!>
        (_.addFunctionLibrary(XXFormsFunctionLibrary))

    object Index {

      val controlAnalysisMap = mutable.LinkedHashMap[String, ElementAnalysis]()
      val controlTypes       = mutable.HashMap[String, mutable.LinkedHashMap[String, ElementAnalysis]]()
      val lhhas              = mutable.Buffer[LHHAAnalysis]()
      val eventHandlers      = mutable.Buffer[EventHandler]()
      val models             = mutable.Buffer[Model]()
      val attributes         = mutable.Buffer[AttributeControl]()

      def indexNewControl(elementAnalysis : ElementAnalysis): Unit = {

        // Index by prefixed id
        controlAnalysisMap += elementAnalysis.prefixedId -> elementAnalysis

        // Index by type
        val controlName = elementAnalysis.localName
        val controlsMap = controlTypes.getOrElseUpdate(controlName, mutable.LinkedHashMap[String, ElementAnalysis]())
        controlsMap += elementAnalysis.prefixedId -> elementAnalysis

        // Index special controls
        elementAnalysis match {
          case e: LHHAAnalysis if ! TopLevelItemsetQNames(e.getParent.element.getQName) => lhhas += e
          case e: EventHandler                                                          => eventHandlers += e
          case e: Model                                                                 => models        += e
          case e: AttributeControl                                                      => attributes    += e
          case _                                                                        =>
        }
      }
    }

    implicit val decodeSAXStore: Decoder[SAXStore] = (c: HCursor) => {

      for {
        eventBufferPosition          <- c.get[Int]("eventBufferPosition")
        eventBuffer                  <- c.get[Array[Byte]]("eventBuffer")
        charBufferPosition           <- c.get[Int]("charBufferPosition")
        charBuffer                   <- c.get[Array[Char]]("charBuffer")
        intBufferPosition            <- c.get[Int]("intBufferPosition")
        intBuffer                    <- c.get[Array[Int]]("intBuffer")
        lineBufferPosition           <- c.get[Int]("lineBufferPosition")
        lineBuffer                   <- c.get[Array[Int]]("lineBuffer")
        systemIdBufferPosition       <- c.get[Int]("systemIdBufferPosition")
        systemIdBuffer               <- c.get[Array[String]]("systemIdBuffer")
        attributeCountBufferPosition <- c.get[Int]("attributeCountBufferPosition")
        attributeCountBuffer         <- c.get[Array[Int]]("attributeCountBuffer")
        attributeCount               <- c.get[Int]("attributeCount")
        stringBuilder                <- c.get[Array[String]]("stringBuilder")
        hasDocumentLocator           <- c.get[Boolean]("hasDocumentLocator")
//        marks                        <- c.get[Array[]]("hasDocumentLocator")
        //    "systemIdBufferPosition" -> Json.fromInt(a.systemIdBufferPosition),
      //    "systemIdBuffer"         -> a.systemIdBuffer.slice(0, a.systemIdBufferPosition).asJson,

      } yield {
        val a = new SAXStore
        a.eventBufferPosition          = eventBufferPosition
        a.eventBuffer                  = eventBuffer
        a.charBufferPosition           = charBufferPosition
        a.charBuffer                   = charBuffer
        a.intBufferPosition            = intBufferPosition
        a.intBuffer                    = intBuffer
        a.lineBufferPosition           = lineBufferPosition
        a.lineBuffer                   = lineBuffer
        a.systemIdBufferPosition       = systemIdBufferPosition
        a.systemIdBuffer               = systemIdBuffer
        a.attributeCountBufferPosition = attributeCountBufferPosition
        a.attributeCountBuffer         = attributeCountBuffer
        a.attributeCount               = attributeCount
        a.stringBuilder                = new ju.ArrayList[String](ju.Arrays.asList(stringBuilder: _*))
        a.hasDocumentLocator           = hasDocumentLocator

        a
      }
    }

//    implicit val encodeSAXStoreMark: Encoder[a.Mark] = (m: a.Mark) => Json.obj(
//      "_id"                          -> Json.fromString(m._id),
//      "eventBufferPosition"          -> Json.fromInt(m.eventBufferPosition),
//      "charBufferPosition"           -> Json.fromInt(m.charBufferPosition),
//      "intBufferPosition"            -> Json.fromInt(m.intBufferPosition),
//      "lineBufferPosition"           -> Json.fromInt(m.lineBufferPosition),
//      "systemIdBufferPosition"       -> Json.fromInt(m.systemIdBufferPosition),
//      "attributeCountBufferPosition" -> Json.fromInt(m.attributeCountBufferPosition),
//      "stringBuilderPosition"        -> Json.fromInt(m.stringBuilderPosition)
//    )
//
//    Json.obj(
//
//
//      //    write(out, if (a.publicId == null) "" else a.publicId)
//      "marks" -> Option(a.marks).map(_.asScala).getOrElse(Nil).asJson
//    )
//  }
//
//    }

    implicit val decodeScope: Decoder[Scope] = (c: HCursor) =>
      for {
//        parent       <- c.get[Scope]("parent") // TODO
        scopeId <- c.get[String]("scopeId")
        idMap   <- c.get[Map[String, String]]("idMap")
      } yield
        scopes.getOrElseUpdate(scopeId, {
          val r = new Scope(None, scopeId)
          idMap foreach (kv => r += kv)
          r
        })

    implicit val decodeAnnotatedTemplate : Decoder[AnnotatedTemplate] = deriveDecoder
  //  implicit val decodeLangRef           : Decoder[LangRef]           = deriveDecoder
    implicit val decodeNamespace         : Decoder[dom.Namespace]     = deriveDecoder
    implicit val decodeBasicCredentials  : Decoder[BasicCredentials]  = deriveDecoder
    implicit val decodeLHHAValue         : Decoder[LHHAValue]         = deriveDecoder

    implicit val decodeQName: Decoder[dom.QName] = (c: HCursor) =>
      for {
        localName <- c.get[String]("localName")
        prefix    <- c.get[String]("prefix")
        uri       <- c.get[String]("uri")
      } yield
        dom.QName(localName: String, prefix: String, uri: String)

    // TODO: Later must be optimized by sharing based on hash!
    implicit val decodeNamespaceMapping: Decoder[NamespaceMapping] = (c: HCursor) =>
      for {
        hash    <- c.get[String]("hash")
        mapping <- c.get[Map[String, String]]("mapping")
      } yield
        NamespaceMapping(hash, mapping)

    implicit lazy val decodeElement: Decoder[dom.Element] = (c: HCursor) =>
      for {
        name     <- c.get[dom.QName]("name")
        atts     <- c.get[List[(dom.QName, String)]]("atts")
        children <- {
          val childIt =
            c.downField("children").values.toIterator.flatten map {
              case s if s.isString => s.asString.map(dom.Text.apply).toRight(throw new IllegalArgumentException)
              case s if s.isObject => decodeElement.decodeJson(s)
              case _ => throw new IllegalArgumentException
            }
          Right(childIt map (_.right.get)) // TODO: `.get`; ideally should get the first error
        }
      } yield {
        val r = dom.Element(name)
        atts foreach { case (name, value) => r.addAttribute(name, value) }
        children foreach r.add
        r
      }

    // Dummy as the source must not contain the `om.Item` case
    implicit val decodeOmItem: Decoder[om.Item] = (c: HCursor) => ???

    implicit def eitherDecoder[A, B](implicit a: Decoder[A], b: Decoder[B]): Decoder[Either[A, B]] = {
      val left:  Decoder[Either[A, B]] = a.map(Left.apply)
      val right: Decoder[Either[A, B]] = b.map(Right.apply)
      left or right
    }

    //decodeValueNode.asInstanceOf[Decoder[Item.ValueNode]]
    implicit val decodeValueNode: Decoder[Item.ValueNode] = (c: HCursor) =>
      for {
        label      <- c.get[LHHAValue]("label")
        attributes <- c.get[List[(QName, String)]]("attributes")
        position   <- c.get[Int]("position")

        help       <- c.get[Option[LHHAValue]]("help")
        hint       <- c.get[Option[LHHAValue]]("hint")
        value      <- c.get[Either[String, List[om.Item]]]("value")

      } yield {
        Item.ValueNode(label, help, hint, value, attributes)(position)
      }

    implicit val decodeItemset: Decoder[Itemset] = (c: HCursor) =>
      for {
        multiple <- c.get[Boolean]("multiple")
        hasCopy  <- c.get[Boolean]("hasCopy")
        children <- c.get[Iterable[Item.ValueNode]]("children") // TODO: ChoiceNode
      } yield {
        val r = new Itemset(multiple, hasCopy)
        children foreach r.addChildItem
        r
      }

    implicit lazy val decodeElementAnalysis: Decoder[ElementAnalysis] = (c: HCursor) =>
      for {
        index             <- c.get[Int]("index")
        element           <- c.get[dom.Element]("element")
        staticId          <- c.get[String]("staticId")
        prefixedId        <- c.get[String]("prefixedId")
        nsRef             <- c.get[Int]("nsRef")
        scopeRef          <- c.get[String]("scopeRef")
        containerScopeRef <- c.get[String]("containerScopeRef")
        modelRef          <- c.get[String]("modelRef")
  //      "langRef"           <- a.lang.asJson,
      } yield {

        val namespaceMapping = NamespaceMapping(collectedNamespaces(nsRef))

        val scope = scopes.getOrElseUpdate(scopeRef, {
          val newScope = new Scope(None, scopeRef)
          // TODO: scopes must first be deserialized…
          newScope
        }) // TODO: parent scope

        val containerScope = scope // TODO

        println(s"xxx decoding element for $element / $prefixedId")

        import org.orbeon.xforms.XFormsNames._

        val newControl =
          element.getQName match {
            case XFORMS_MODEL_QNAME =>
              new Model(index, element, controlStack.headOption, None, staticId, prefixedId, namespaceMapping, scope, containerScope)
            case XFORMS_INSTANCE_QNAME =>

              val instance =
                for {
                  readonly              <- c.get[Boolean]("readonly")
                  cache                 <- c.get[Boolean]("cache")
                  timeToLive            <- c.get[Long]("timeToLive")
                  exposeXPathTypes      <- c.get[Boolean]("exposeXPathTypes")
                  indexIds              <- c.get[Boolean]("indexIds")
                  indexClasses          <- c.get[Boolean]("indexClasses")
                  isLaxValidation       <- c.get[Boolean]("isLaxValidation")
                  isStrictValidation    <- c.get[Boolean]("isStrictValidation")
                  isSchemaValidation    <- c.get[Boolean]("isSchemaValidation")
                  indexClasses          <- c.get[Boolean]("indexClasses")
//                  credentials           <- c.get[BasicCredentials]("credentials")
                  excludeResultPrefixes <- c.get[Set[String]]("excludeResultPrefixes")
                  useInlineContent      <- c.get[Boolean]("useInlineContent")
                  useExternalContent    <- c.get[Boolean]("useExternalContent")
                  instanceSource        <- c.get[Option[String]]("instanceSource")
                  inlineRootElemOpt     <- c.get[Option[dom.Element]]("inlineRootElem")
                } yield {

                  val r =
                    new Instance(
                      index,
                      element,
                      controlStack.headOption,
                      None,
                      staticId,
                      prefixedId,
                      namespaceMapping,
                      scope,
                      containerScope,
                      InstanceMetadata(
                        readonly              = readonly,
                        cache                 = cache,
                        timeToLive            = timeToLive,
                        handleXInclude        = false, // not serialized
                        exposeXPathTypes      = exposeXPathTypes,
                        indexIds              = indexIds,
                        indexClasses          = indexClasses,
                        isLaxValidation       = isLaxValidation,
                        isStrictValidation    = isStrictValidation,
                        isSchemaValidation    = isSchemaValidation,
                        credentials           = null, // XXX TODO
                        excludeResultPrefixes = excludeResultPrefixes,
                        inlineRootElemOpt     = inlineRootElemOpt,
                        useInlineContent      = useInlineContent,
                        useExternalContent    = useExternalContent,
                        instanceSource        = instanceSource,
                        dependencyURL         = None // not serialized
                      )
                    )
                  r.useExternalContent
                  r
                }

              instance.right.get // XXX TODO

            case XFORMS_BIND_QNAME =>

              val staticBind =
                new StaticBind(index, element, controlStack.headOption, None, staticId, prefixedId, namespaceMapping, scope, containerScope,
                  isTopLevelPart  = true,
                  functionLibrary = functionLibrary
                )

              implicit val decodeTypeMIP: Decoder[staticBind.TypeMIP] = (c: HCursor) =>
                for {
                  id         <- c.get[String]("id")
                  datatype   <- c.get[String]("datatype")
                } yield
                  new staticBind.TypeMIP(id, datatype)

              implicit val decodeXPathMIP: Decoder[staticBind.XPathMIP] = (c: HCursor) =>
                for {
                  id         <- c.get[String]("id")
                  name       <- c.get[String]("name")
                  level      <- c.get[ValidationLevel]("level")
                  expression <- c.get[String]("expression")
                } yield
                  new staticBind.XPathMIP(id, name, level, expression)

              //              implicit val encodeTypeMIP: Encoder[c.TypeMIP] = (a: c.TypeMIP) => Json.obj(
//                "id"         -> Json.fromString(a.id),
//                "datatype"   -> Json.fromString(a.datatype)
//              )
//
//              implicit val encodeWhitespaceMIP: Encoder[c.WhitespaceMIP] = (a: c.WhitespaceMIP) => Json.obj(
//                "id"         -> Json.fromString(a.id),
//                "policy"     -> a.policy.asJson
//              )
//
//              implicit val encodeXPathMIP: Encoder[c.XPathMIP] = (a: c.XPathMIP) => Json.obj(
//                "id"         -> Json.fromString(a.id),
//                "name"       -> Json.fromString(a.name),
//                "level"      -> a.level.asJson,
//                "expression" -> Json.fromString(a.expression)
//              )

              for {
                typeMIPOpt                  <- c.get[Option[staticBind.TypeMIP]]("typeMIPOpt")
//                dataType                    <- c.get[]("dataType")
//                nonPreserveWhitespaceMIPOpt <- c.get[]("nonPreserveWhitespaceMIPOpt")
                mipNameToXPathMIP           <- c.get[Iterable[(String, List[staticBind.XPathMIP])]]("mipNameToXPathMIP")
                customMIPNameToXPathMIP     <- c.get[Map[String, List[staticBind.XPathMIP]]]("customMIPNameToXPathMIP")
              } yield {
                staticBind.typeMIPOpt              = typeMIPOpt
                staticBind.mipNameToXPathMIP       = mipNameToXPathMIP
                staticBind.customMIPNameToXPathMIP = customMIPNameToXPathMIP
              }

            //              var figuredAllBindRefAnalysis: Boolean = false
//
//              var recalculateOrder : Option[List[StaticBind]] = None
//              var defaultValueOrder: Option[List[StaticBind]] = None

              staticBind

            case XFORMS_INPUT_QNAME =>
              new InputControl(index, element, controlStack.headOption, None, staticId, prefixedId, namespaceMapping, scope, containerScope)
            case XFORMS_OUTPUT_QNAME =>

              val output =
                for {
                  isImageMediatype     <- c.get[Boolean]("isImageMediatype")
                  isHtmlMediatype      <- c.get[Boolean]("isHtmlMediatype")
                  isDownloadAppearance <- c.get[Boolean]("isDownloadAppearance")
                  staticValue          <- c.get[Option[String]]("staticValue")
                } yield
                  new OutputControl(index, element, controlStack.headOption, None, staticId, prefixedId, namespaceMapping, scope, containerScope,
                    isImageMediatype,
                    isHtmlMediatype,
                    isDownloadAppearance,
                    staticValue
                  )

              output.right.get // XXX TODO

            case XFORMS_SELECT_QNAME | XFORMS_SELECT1_QNAME =>

              val select =
                for {
                  staticItemset    <- c.get[Option[Itemset]]("staticItemset")
                  useCopy          <- c.get[Boolean]("useCopy")
                  mustEncodeValues <- c.get[Option[Boolean]]("mustEncodeValues")
                } yield
                  new SelectionControl(index, element, controlStack.headOption, None, staticId, prefixedId, namespaceMapping, scope, containerScope,
                    staticItemset,
                    useCopy,
                    mustEncodeValues
                  )

              select.right.get // XXX TODO

            case XFORMS_TEXTAREA_QNAME =>
              new TextareaControl(index, element, controlStack.headOption, None, staticId, prefixedId, namespaceMapping, scope, containerScope)
            case XFORMS_SECRET_QNAME =>
              new SecretControl(index, element, controlStack.headOption, None, staticId, prefixedId, namespaceMapping, scope, containerScope)
            case qName if LHHA.QNamesSet(qName) =>

              val lhha =
                for {
                  staticValue                <- c.get[Option[String]]("staticValue")
                  isPlaceholder              <- c.get[Boolean]("isPlaceholder")
                  containsHTML               <- c.get[Boolean]("containsHTML")
                  hasLocalMinimalAppearance  <- c.get[Boolean]("hasLocalMinimalAppearance")
                  hasLocalFullAppearance     <- c.get[Boolean]("hasLocalFullAppearance")
                  hasLocalLeftAppearance     <- c.get[Boolean]("hasLocalLeftAppearance")
                } yield
                  new LHHAAnalysis(index, element, controlStack.headOption, None, staticId, prefixedId, namespaceMapping, scope, containerScope,
                    staticValue,
                    isPlaceholder,
                    containsHTML,
                    hasLocalMinimalAppearance,
                    hasLocalFullAppearance,
                    hasLocalLeftAppearance
                  )

              lhha.right.get // XXX TODO

            case ROOT_QNAME =>
              new RootControl(index, element, staticId, prefixedId, namespaceMapping, scope, containerScope, None)
            case XFORMS_TRIGGER_QNAME =>
              new TriggerControl(index, element, controlStack.headOption, None, staticId, prefixedId, namespaceMapping, scope, containerScope)
            case qName: QName if EventHandler.isAction(qName) =>

              if (EventHandler.isEventHandler(element)) {

                val eventHandler =
                  for {
                    keyText                <- c.get[Option[String]]("keyText")
                    keyModifiers           <- c.get[Set[Modifier]]("keyModifiers")
                    eventNames             <- c.get[Set[String]]("eventNames")
                    isAllEvents            <- c.get[Boolean]("isAllEvents")
                    isCapturePhaseOnly     <- c.get[Boolean]("isCapturePhaseOnly")
                    isTargetPhase          <- c.get[Boolean]("isTargetPhase")
                    isBubblingPhase        <- c.get[Boolean]("isBubblingPhase")
                    propagate              <- c.get[Propagate]("propagate")
                    isPerformDefaultAction <- c.get[Perform]("isPerformDefaultAction")
                    isPhantom              <- c.get[Boolean]("isPhantom")
                    isIfNonRelevant        <- c.get[Boolean]("isIfNonRelevant")
                    isXBLHandler           <- c.get[Boolean]("isXBLHandler")
                    observersPrefixedIds   <- c.get[Set[String]]("observersPrefixedIds")
                    targetPrefixedIds   <- c.get[Set[String]]("targetPrefixedIds")
                  } yield {
                    val eh =
                      if (EventHandler.isContainerAction(element.getQName)) {
                        println(s"xxx is container action")
                        new EventHandler(
                          index,
                          element,
                          controlStack.headOption,
                          None,
                          staticId,
                          prefixedId,
                          namespaceMapping,
                          scope,
                          containerScope,
                          keyText,
                          Set.empty,//keyModifiers,
                          eventNames,
                          isAllEvents,
                          isCapturePhaseOnly,
                          isTargetPhase,
                          isBubblingPhase,
                          Propagate.Continue,// propagate,
                          Perform.Perform,//isPerformDefaultAction,
                          isPhantom,
                          isIfNonRelevant,
                          isXBLHandler
                        ) with WithChildrenTrait
                      } else
                        new EventHandler(
                          index,
                          element,
                          controlStack.headOption,
                          None,
                          staticId,
                          prefixedId,
                          namespaceMapping,
                          scope,
                          containerScope,
                          keyText,
                          keyModifiers,
                          eventNames,
                          isAllEvents,
                          isCapturePhaseOnly,
                          isTargetPhase,
                          isBubblingPhase,
                          propagate,
                          isPerformDefaultAction,
                          isPhantom,
                          isIfNonRelevant,
                          isXBLHandler
                        )
                    eh.observersPrefixedIds = observersPrefixedIds
                    eh.targetPrefixedIds    = targetPrefixedIds

                    eh
                  }

                eventHandler.right.get // XXX TODO

              } else {
                if (EventHandler.isContainerAction(element.getQName))
                  new ElementAnalysis(index, element, controlStack.headOption, None, staticId, prefixedId, namespaceMapping, scope, containerScope) with ActionTrait with WithChildrenTrait
                else
                  new ElementAnalysis(index, element, controlStack.headOption, None, staticId, prefixedId, namespaceMapping, scope, containerScope) with ActionTrait
              }

            case qName if VariableAnalysis.VariableQNames(qName) =>

              val variable =
                for {
                  name                <- c.get[String]("name")
                  expressionStringOpt <- c.get[Option[String]]("expressionStringOpt")
                } yield {
                  if (controlStack.headOption exists (_.localName == XFORMS_MODEL_QNAME.localName))
                    new ModelVariable(
                      index,
                      element,
                      controlStack.headOption,
                      None,
                      staticId,
                      prefixedId,
                      namespaceMapping,
                      scope,
                      containerScope,
                      name,
                      expressionStringOpt
                    )
                  else
                    new VariableControl(
                      index,
                      element,
                      controlStack.headOption,
                      None,
                      staticId,
                      prefixedId,
                      namespaceMapping,
                      scope,
                      containerScope,
                      name,
                      expressionStringOpt
                    )
                }

              variable.right.get // XXX TODO

            case _ =>
              new ElementAnalysis(index, element, controlStack.headOption, None, staticId, prefixedId, namespaceMapping, scope, containerScope) {}
          }

        Index.indexNewControl(newControl)

        newControl match {
          case withChildren: WithChildrenTrait =>

            println(s"xxx checking children")

            controlStack ::= withChildren
            c.get[Iterable[ElementAnalysis]]("children") foreach withChildren.addChildren
            controlStack = controlStack.tail
          case _ =>
        }

        newControl
      }

    implicit val decodeTopLevelPartAnalysis: Decoder[TopLevelPartAnalysis] = (c: HCursor) =>
      for {
        startScope       <- c.get[Scope]("startScope")
        topLevelControls <- c.get[Iterable[ElementAnalysis]]("topLevelControls") // TODO
      } yield
        TopLevelPartAnalysisImpl(
          startScope,
          topLevelControls,
          Index.controlAnalysisMap,
          Index.controlTypes,
          Index.lhhas,
          Index.eventHandlers,
          Index.models,
          Index.attributes,
          functionLibrary
        )

    implicit val decodeXFormsStaticState: Decoder[XFormsStaticState] = (c: HCursor) =>
      for {
        namespaces           <- c.get[IndexedSeq[Map[String, String]]]("namespaces")
        _ = {
          collectedNamespaces = namespaces
        }
        nonDefaultProperties <- c.get[Map[String, (String, Boolean)]]("nonDefaultProperties")
        topLevelPart         <- c.get[TopLevelPartAnalysis]("topLevelPart")
        template             <- c.get[SAXStore]("template")
      } yield {
        XFormsStaticStateImpl(nonDefaultProperties, Int.MaxValue, topLevelPart, AnnotatedTemplate(template).some) // TODO: serialize `globalMaxSizeProperty` from server
      }

    decode[XFormsStaticState](jsonString) match {
      case Left(error) =>
        println(error.getMessage)
        throw error
      case Right(result) => result
    }
  }
}


object XFormsStaticStateImpl {

  def apply(
    _nonDefaultProperties : Map[String, (String, Boolean)],
    globalMaxSizeProperty : Int,
    _topLevelPart         : TopLevelPartAnalysis,
    _template             : Option[AnnotatedTemplate])(implicit
    _logger               : IndentedLogger
  ): XFormsStaticState = {

    val staticProperties = new XFormsStaticStateStaticPropertiesImpl(_nonDefaultProperties, globalMaxSizeProperty) {
      protected def isPEFeatureEnabled(featureRequested: Boolean, featureName: String): Boolean = featureRequested
    }

    val dynamicProperties = new XFormsStaticStateDynamicPropertiesImpl(_nonDefaultProperties, staticProperties, _topLevelPart)

    new XFormsStaticState {

      def getIndentedLogger: IndentedLogger = _logger

      def digest: String = "" // XXX TODO
      def encodedState: String = "" // XXX TODO

      val topLevelPart: TopLevelPartAnalysis = _topLevelPart
      val template: Option[AnnotatedTemplate] = _template

      def isHTMLDocument: Boolean = true // TODO

      // TODO: serialize/deserialize
      def assets: XFormsAssets = XFormsAssets(Nil, Nil) // XXX TODO
      def sanitizeInput: String => String = identity // XXX TODO

      def nonDefaultProperties: Map[String, (String, Boolean)] = _nonDefaultProperties

      // Delegate (Scala 3's `export` would be nice!)
      // export staticProperties._
      // export dynamicProperties._
      def isClientStateHandling               : Boolean          = staticProperties.isClientStateHandling
      def isServerStateHandling               : Boolean          = staticProperties.isServerStateHandling
      def isXPathAnalysis                     : Boolean          = staticProperties.isXPathAnalysis
      def isCalculateDependencies             : Boolean          = staticProperties.isCalculateDependencies
      def isInlineResources                   : Boolean          = staticProperties.isInlineResources
      def uploadMaxSize                       : MaximumSize      = staticProperties.uploadMaxSize
      def uploadMaxSizeAggregate              : MaximumSize      = staticProperties.uploadMaxSizeAggregate
      def staticProperty       (name: String) : Any              = staticProperties.staticProperty       (name)
      def staticStringProperty (name: String) : String           = staticProperties.staticStringProperty (name)
      def staticBooleanProperty(name: String) : Boolean          = staticProperties.staticBooleanProperty(name)
      def staticIntProperty    (name: String) : Int              = staticProperties.staticIntProperty    (name)
      def clientNonDefaultProperties          : Map[String, Any] = staticProperties.clientNonDefaultProperties
      def allowedExternalEvents               : Set[String]      = staticProperties.allowedExternalEvents

      def uploadMaxSizeAggregateExpression        : Option[StaticXPath.CompiledExpression]      = dynamicProperties.uploadMaxSizeAggregateExpression
      def propertyMaybeAsExpression(name: String) : Either[Any, StaticXPath.CompiledExpression] = dynamicProperties.propertyMaybeAsExpression(name)
    }
  }
}

object TopLevelPartAnalysisImpl {

  def apply(
    _startScope         : Scope,
    _topLevelControls   : Iterable[ElementAnalysis],
    _controlAnalysisMap : mutable.LinkedHashMap[String, ElementAnalysis],
    _controlTypes       : mutable.HashMap[String, mutable.LinkedHashMap[String, ElementAnalysis]],
    lhhas               : mutable.Buffer[LHHAAnalysis],
    eventHandlers       : mutable.Buffer[EventHandler],
    models              : mutable.Buffer[Model],
    attributes          : mutable.Buffer[AttributeControl],
    _functionLibrary    : FunctionLibrary)(implicit
    logger              : IndentedLogger
  ): TopLevelPartAnalysis = {

    val partAnalysis =
      new TopLevelPartAnalysis
        with PartModelAnalysis
        with PartControlsAnalysis
        with PartEventHandlerAnalysis {

        def bindingIncludes: Set[String] = Set.empty // XXX TODO

        def bindingsIncludesAreUpToDate: Boolean = true

        def debugOutOfDateBindingsIncludes: String = "" // XXX TODO

        override val controlAnalysisMap: mutable.LinkedHashMap[String, ElementAnalysis] = _controlAnalysisMap
        override val controlTypes: mutable.HashMap[String, mutable.LinkedHashMap[String, ElementAnalysis]] = _controlTypes

        val startScope: Scope = _startScope

        def findControlAnalysis(prefixedId: String): Option[ElementAnalysis] = controlAnalysisMap.get(prefixedId)

        def parent: Option[PartAnalysis] = None
        def isTopLevelPart: Boolean = true

        val functionLibrary: FunctionLibrary = _functionLibrary

        // XXX TODO
        def getNamespaceMapping(prefixedId: String): Option[NamespaceMapping] =
          Some(NamespaceMapping(Map("xf" -> "http://www.w3.org/2002/xforms", "xh" -> "http://www.w3.org/1999/xhtml")))

        def hasControls: Boolean =
          getTopLevelControls.nonEmpty || (iterateGlobals map (_.compactShadowTree.getRootElement)).nonEmpty // TODO: duplicated from `StaticPartAnalysisImpl`

        override val getTopLevelControls: List[ElementAnalysis] = _topLevelControls.toList

        def getNamespaceMapping(prefix: String, element: dom.Element): NamespaceMapping = {

          val id = element.idOrThrow
          val prefixedId = if (prefix ne null) prefix + id else id

          getNamespaceMapping(prefixedId) getOrElse
            (throw new IllegalStateException(s"namespace mappings not cached for prefix `$prefix` on element `${element.toDebugString}`"))
        }

        def getMark(prefixedId: String): Option[SAXStore#Mark] = None // XXX TODO

        def iterateGlobals: Iterator[Global] = Iterator.empty // XXX TODO

        def allXblAssetsMaybeDuplicates: Iterable[XBLAssets] = Nil

        def containingScope(prefixedId: String): Scope = ???

        def scopeForPrefixedId(prefixedId: String): Scope =
          findControlAnalysis(prefixedId) map
            (_.scope)                     getOrElse
            (throw new IllegalStateException(s"missing scope information for $prefixedId"))

        def scriptsByPrefixedId: Map[String, StaticScript] = Map.empty // XXX TODO

        def uniqueJsScripts: List[ShareableScript] = Nil // XXX TODO

        def baselineResources: (List[String], List[String]) = (Nil, Nil) // XXX TODO
      }

    for (model <- models)
      partAnalysis.indexModel(model)

    for (lhha <- lhhas)
      PartAnalysisSupport.attachToControl(partAnalysis.findControlAnalysis, lhha)

    partAnalysis.registerEventHandlers(eventHandlers)
    partAnalysis.indexAttributeControls(attributes)

//    ElementAnalysisTreeBuilder.setModelAndLangOnAllDescendants(partAnalysisCtx, container)

    partAnalysis
  }
}