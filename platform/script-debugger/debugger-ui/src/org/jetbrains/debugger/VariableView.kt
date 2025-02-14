/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.debugger

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.util.Consumer
import com.intellij.util.SmartList
import com.intellij.util.ThreeState
import com.intellij.xdebugger.XSourcePositionWrapper
import com.intellij.xdebugger.frame.*
import com.intellij.xdebugger.frame.presentation.XKeywordValuePresentation
import com.intellij.xdebugger.frame.presentation.XNumericValuePresentation
import com.intellij.xdebugger.frame.presentation.XStringValuePresentation
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import org.jetbrains.concurrency.Promise
import org.jetbrains.debugger.values.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import javax.swing.Icon

fun VariableView(variable: Variable, context: VariableContext) = VariableView(variable.name, variable, context)

class VariableView(name: String, private val variable: Variable, private val context: VariableContext) : XNamedValue(name), VariableContext {
  @Volatile private var value: Value? = null
  // lazy computed
  private var memberFilter: MemberFilter? = null

  @Volatile private var remainingChildren: List<Variable>? = null
  @Volatile private var remainingChildrenOffset: Int = 0

  override fun watchableAsEvaluationExpression() = context.watchableAsEvaluationExpression()

  override fun getViewSupport() = context.viewSupport

  override fun getParent() = context

  override fun getMemberFilter(): Promise<MemberFilter> {
    return context.viewSupport.getMemberFilter(this)
  }

  override fun computePresentation(node: XValueNode, place: XValuePlace) {
    value = variable.value
    if (value != null) {
      computePresentation(value!!, node)
      return
    }

    if (variable !is ObjectProperty || variable.getter == null) {
      // it is "used" expression (WEB-6779 Debugger/Variables: Automatically show used variables)
      evaluateContext.evaluate(variable.name)
        .done(node) {
          if (it.wasThrown) {
            setEvaluatedValue(viewSupport.transformErrorOnGetUsedReferenceValue(value, null), null, node)
          }
          else {
            value = it.value
            computePresentation(it.value, node)
          }
        }
        .rejected(node) { setEvaluatedValue(viewSupport.transformErrorOnGetUsedReferenceValue(null, it.message), it.message, node) }
      return
    }

    node.setPresentation(null, object : XValuePresentation() {
      override fun renderValue(renderer: XValuePresentation.XValueTextRenderer) {
        renderer.renderValue("\u2026")
      }
    }, false)
    node.setFullValueEvaluator(object : XFullValueEvaluator(" (invoke getter)") {
      override fun startEvaluation(callback: XFullValueEvaluator.XFullValueEvaluationCallback) {
        val valueModifier = variable.valueModifier
        assert(valueModifier != null)
        valueModifier!!.evaluateGet(variable, evaluateContext)
          .done(node) {
            callback.evaluated("")
            setEvaluatedValue(it, null, node)
          }
      }
    }.setShowValuePopup(false))
  }

  private fun setEvaluatedValue(value: Value?, error: String?, node: XValueNode) {
    if (value == null) {
      node.setPresentation(AllIcons.Debugger.Db_primitive, null, error ?: "Internal Error", false)
    }
    else {
      this.value = value
      computePresentation(value, node)
    }
  }

  private fun computePresentation(value: Value, node: XValueNode) {
    when (value.type) {
      ValueType.OBJECT, ValueType.NODE -> context.viewSupport.computeObjectPresentation((value as ObjectValue), variable, context, node, icon)

      ValueType.FUNCTION -> node.setPresentation(icon, ObjectValuePresentation(trimFunctionDescription(value)), true)

      ValueType.ARRAY -> context.viewSupport.computeArrayPresentation(value, variable, context, node, icon)

      ValueType.BOOLEAN, ValueType.NULL, ValueType.UNDEFINED -> node.setPresentation(icon, XKeywordValuePresentation(value.valueString!!), false)

      ValueType.NUMBER -> node.setPresentation(icon, createNumberPresentation(value.valueString!!), false)

      ValueType.STRING -> {
        node.setPresentation(icon, XStringValuePresentation(value.valueString!!), false)
        // isTruncated in terms of debugger backend, not in our terms (i.e. sometimes we cannot control truncation),
        // so, even in case of StringValue, we check value string length
        if ((value is StringValue && value.isTruncated) || value.valueString!!.length > XValueNode.MAX_VALUE_LENGTH) {
          node.setFullValueEvaluator(MyFullValueEvaluator(value))
        }
      }

      else -> node.setPresentation(icon, null, value.valueString!!, true)
    }
  }

  override fun computeChildren(node: XCompositeNode) {
    node.setAlreadySorted(true)

    if (value !is ObjectValue) {
      node.addChildren(XValueChildrenList.EMPTY, true)
      return
    }

    val list = remainingChildren
    if (list != null) {
      val to = Math.min(remainingChildrenOffset + XCompositeNode.MAX_CHILDREN_TO_SHOW, list.size)
      val isLast = to == list.size
      node.addChildren(createVariablesList(list, remainingChildrenOffset, to, this, memberFilter), isLast)
      if (!isLast) {
        node.tooManyChildren(list.size - to)
        remainingChildrenOffset += XCompositeNode.MAX_CHILDREN_TO_SHOW
      }
      return
    }

    val objectValue = value as ObjectValue
    val hasNamedProperties = objectValue.hasProperties() != ThreeState.NO
    val hasIndexedProperties = objectValue.hasIndexedProperties() != ThreeState.NO
    val promises = SmartList<Promise<*>>()
    val additionalProperties = viewSupport.computeAdditionalObjectProperties(objectValue, variable, this, node)
    if (additionalProperties != null) {
      promises.add(additionalProperties)
    }

    // we don't support indexed properties if additional properties added - behavior is undefined if object has indexed properties and additional properties also specified
    if (hasIndexedProperties) {
      promises.add(computeIndexedProperties(objectValue as ArrayValue, node, !hasNamedProperties && additionalProperties == null))
    }

    if (hasNamedProperties) {
      // named properties should be added after additional properties
      if (additionalProperties == null || additionalProperties.state != Promise.State.PENDING) {
        promises.add(computeNamedProperties(objectValue, node, !hasIndexedProperties && additionalProperties == null))
      }
      else {
        promises.add(additionalProperties.thenAsync(node) { computeNamedProperties(objectValue, node, true) })
      }
    }

    if (hasIndexedProperties == hasNamedProperties || additionalProperties != null) {
//      Promise.all(promises).processed(object : ObsolescentConsumer<Any>(node) {
//        override fun consume(aVoid: Any) = node.addChildren(XValueChildrenList.EMPTY, true)
//      })
    }
  }

  abstract class ObsolescentIndexedVariablesConsumer(protected val node: XCompositeNode) : IndexedVariablesConsumer() {
    override fun isObsolete() = node.isObsolete
  }

  private fun computeIndexedProperties(value: ArrayValue, node: XCompositeNode, isLastChildren: Boolean): Promise<*> {
    return value.getIndexedProperties(0, value.length, XCompositeNode.MAX_CHILDREN_TO_SHOW, object : ObsolescentIndexedVariablesConsumer(node) {
      override fun consumeRanges(ranges: IntArray?) {
        if (ranges == null) {
          val groupList = XValueChildrenList()
          LazyVariablesGroup.addGroups(value, LazyVariablesGroup.GROUP_FACTORY, groupList, 0, value.length, XCompositeNode.MAX_CHILDREN_TO_SHOW, this@VariableView)
          node.addChildren(groupList, isLastChildren)
        }
        else {
          LazyVariablesGroup.addRanges(value, ranges, node, this@VariableView, isLastChildren)
        }
      }

      override fun consumeVariables(variables: List<Variable>) {
        node.addChildren(createVariablesList(variables, this@VariableView, null), isLastChildren)
      }
    }, null)
  }

  private fun computeNamedProperties(value: ObjectValue, node: XCompositeNode, isLastChildren: Boolean) = processVariables(this, value.properties, node) { memberFilter, variables ->
    this@VariableView.memberFilter = memberFilter

    if (value.type == ValueType.ARRAY && value !is ArrayValue) {
      computeArrayRanges(variables, node)
      return@processVariables
    }

    var functionValue = value as? FunctionValue
    if (functionValue != null && functionValue.hasScopes() == ThreeState.NO) {
      functionValue = null
    }

    remainingChildren = processNamedObjectProperties(variables, node, this@VariableView, memberFilter, XCompositeNode.MAX_CHILDREN_TO_SHOW, isLastChildren && functionValue == null)
    if (remainingChildren != null) {
      remainingChildrenOffset = XCompositeNode.MAX_CHILDREN_TO_SHOW
    }

    if (functionValue != null) {
      // we pass context as variable context instead of this variable value - we cannot watch function scopes variables, so, this variable name doesn't matter
      node.addChildren(XValueChildrenList.bottomGroup(FunctionScopesValueGroup(functionValue, context)), isLastChildren)
    }
  }

  private fun computeArrayRanges(properties: List<Variable>, node: XCompositeNode) {
    val variables = filterAndSort(properties, memberFilter!!)
    var count = variables.size
    val bucketSize = XCompositeNode.MAX_CHILDREN_TO_SHOW
    if (count <= bucketSize) {
      node.addChildren(createVariablesList(variables, this, null), true)
      return
    }

    while (count > 0) {
      if (Character.isDigit(variables.get(count - 1).name.get(0))) {
        break
      }
      count--
    }

    val groupList = XValueChildrenList()
    if (count > 0) {
      LazyVariablesGroup.addGroups(variables, VariablesGroup.GROUP_FACTORY, groupList, 0, count, bucketSize, this)
    }

    var notGroupedVariablesOffset: Int
    if ((variables.size - count) > bucketSize) {
      notGroupedVariablesOffset = variables.size
      while (notGroupedVariablesOffset > 0) {
        if (!variables.get(notGroupedVariablesOffset - 1).name.startsWith("__")) {
          break
        }
        notGroupedVariablesOffset--
      }

      if (notGroupedVariablesOffset > 0) {
        LazyVariablesGroup.addGroups(variables, VariablesGroup.GROUP_FACTORY, groupList, count, notGroupedVariablesOffset, bucketSize, this)
      }
    }
    else {
      notGroupedVariablesOffset = count
    }

    for (i in notGroupedVariablesOffset..variables.size - 1) {
      val variable = variables.get(i)
      groupList.add(VariableView(memberFilter!!.rawNameToSource(variable), variable, this))
    }

    node.addChildren(groupList, true)
  }

  private val icon: Icon
    get() = getIcon(value!!)

  override fun getModifier(): XValueModifier? {
    if (!variable.isMutable) {
      return null
    }

    return object : XValueModifier() {
      override fun getInitialValueEditorText(): String? {
        if (value!!.type == ValueType.STRING) {
          val string = value!!.valueString!!
          val builder = StringBuilder(string.length)
          builder.append('"')
          StringUtil.escapeStringCharacters(string.length, string, builder)
          builder.append('"')
          return builder.toString()
        }
        else {
          return if (value!!.type.isObjectType) null else value!!.valueString
        }
      }

      override fun setValue(expression: String, callback: XValueModifier.XModificationCallback) {
        variable.valueModifier!!.setValue(variable, expression, evaluateContext)
//          .done(Consumer<Any> {
//            value = null
//            callback.valueModified()
//          })
//          .rejected(createErrorMessageConsumer(callback))
      }
    }
  }

  override fun getEvaluateContext() = context.evaluateContext

  fun getValue() = variable.value

  override fun canNavigateToSource() = value is FunctionValue || viewSupport.canNavigateToSource(variable, context)

  override fun computeSourcePosition(navigatable: XNavigatable) {
    if (value is FunctionValue) {
      (value as FunctionValue).resolve()
        .done { function ->
          viewSupport.vm!!.scriptManager.getScript(function)
            .done {
              val position = if (it == null) null else viewSupport.getSourceInfo(null, it, function.openParenLine, function.openParenColumn)
              navigatable.setSourcePosition(if (position == null)
                null
              else
                object : XSourcePositionWrapper(position) {
                  override fun createNavigatable(project: Project): Navigatable {
                    val result = PsiVisitors.visit(myPosition, project, object : PsiVisitors.Visitor<Navigatable>() {
                      override fun visit(element: PsiElement, positionOffset: Int, document: Document): Navigatable? {
                        // element will be "open paren", but we should navigate to function name,
                        // we cannot use specific PSI type here (like JSFunction), so, we try to find reference expression (i.e. name expression)
                        var referenceCandidate: PsiElement? = element
                        var psiReference: PsiElement? = null
                        while (true) {
                          referenceCandidate = referenceCandidate?.prevSibling ?: break
                          if (referenceCandidate is PsiReference) {
                            psiReference = referenceCandidate
                            break
                          }
                        }

                        if (psiReference == null) {
                          referenceCandidate = element.parent
                          while (true) {
                            referenceCandidate = referenceCandidate?.prevSibling ?: break
                            if (referenceCandidate is PsiReference) {
                              psiReference = referenceCandidate
                              break
                            }
                          }
                        }

                        return (if (psiReference == null) element.navigationElement else psiReference.navigationElement) as? Navigatable
                      }
                    }, null)
                    return result ?: super.createNavigatable(project)
                  }
                })
            }
        }
    }
    else {
      viewSupport.computeSourcePosition(name, value!!, variable, context, navigatable)
    }
  }

  override fun computeInlineDebuggerData(callback: XInlineDebuggerDataCallback) = viewSupport.computeInlineDebuggerData(name, variable, context, callback)

  override fun getEvaluationExpression(): String? {
    if (!watchableAsEvaluationExpression()) {
      return null
    }

    val list = SmartList(variable.name)
    var parent: VariableContext? = context
    while (parent != null && parent.name != null) {
      list.add(parent.name!!)
      parent = parent.parent
    }
    return context.viewSupport.propertyNamesToString(list, false)
  }

  private class MyFullValueEvaluator(private val value: Value) : XFullValueEvaluator(if (value is StringValue) value.length else value.valueString!!.length) {
    override fun startEvaluation(callback: XFullValueEvaluator.XFullValueEvaluationCallback) {
      if (value !is StringValue || !value.isTruncated) {
        callback.evaluated(value.valueString!!)
        return
      }

      val evaluated = AtomicBoolean()
      value.fullString
        .done {
          if (!callback.isObsolete && evaluated.compareAndSet(false, true)) {
            callback.evaluated(value.valueString!!)
          }
        }
        .rejected(createErrorMessageConsumer(callback))
    }
  }

  override fun getScope() = context.scope

  companion object {
    fun setObjectPresentation(value: ObjectValue, icon: Icon, node: XValueNode) {
      node.setPresentation(icon, ObjectValuePresentation(getObjectValueDescription(value)), value.hasProperties() != ThreeState.NO)
    }

    fun setArrayPresentation(value: Value, context: VariableContext, icon: Icon, node: XValueNode) {
      assert(value.type == ValueType.ARRAY)

      if (value is ArrayValue) {
        val length = value.length
        node.setPresentation(icon, ArrayPresentation(length, value.className), length > 0)
        return
      }

      val valueString = value.valueString
      // only WIP reports normal description
      if (valueString != null && valueString.endsWith("]") && ARRAY_DESCRIPTION_PATTERN.matcher(valueString).find()) {
        node.setPresentation(icon, null, valueString, true)
      }
      else {
        context.evaluateContext.evaluate("a.length", Collections.singletonMap<String, Any>("a", value), false)
          .done(node) { node.setPresentation(icon, null, "Array[${it.value.valueString}]", true) }
          .rejected(node) { node.setPresentation(icon, null, "Internal error: $it", false) }
      }
    }

    fun getIcon(value: Value): Icon {
      val type = value.type
      return when (type) {
        ValueType.FUNCTION -> AllIcons.Nodes.Function
        ValueType.ARRAY -> AllIcons.Debugger.Db_array
        else -> if (type.isObjectType) AllIcons.Debugger.Value else AllIcons.Debugger.Db_primitive
      }
    }
  }
}

fun getClassName(value: ObjectValue): String {
  val className = value.className
  return if (className.isNullOrEmpty()) "Object" else className!!
}

fun getObjectValueDescription(value: ObjectValue): String {
  val description = value.valueString
  return if (description.isNullOrEmpty()) getClassName(value) else description!!
}

internal fun trimFunctionDescription(value: Value): String {
  val presentableValue = value.valueString ?: return ""

  var endIndex = 0
  while (endIndex < presentableValue.length && !StringUtil.isLineBreak(presentableValue.get(endIndex))) {
    endIndex++
  }
  while (endIndex > 0 && Character.isWhitespace(presentableValue.get(endIndex - 1))) {
    endIndex--
  }
  return presentableValue.substring(0, endIndex)
}

private fun createNumberPresentation(value: String): XValuePresentation {
  return if (value == PrimitiveValue.NA_N_VALUE || value == PrimitiveValue.INFINITY_VALUE) XKeywordValuePresentation(value) else XNumericValuePresentation(value)
}

private fun createErrorMessageConsumer(callback: XValueCallback): Consumer<Throwable> {
  return object : Consumer<Throwable> {
    override fun consume(error: Throwable) {
      callback.errorOccurred(error.message!!)
    }
  }
}

private val ARRAY_DESCRIPTION_PATTERN = Pattern.compile("^[a-zA-Z\\d]+\\[\\d+\\]$")

private class ArrayPresentation(length: Int, className: String?) : XValuePresentation() {
  private val length = Integer.toString(length)
  private val className = if (className.isNullOrEmpty()) "Array" else className!!

  override fun renderValue(renderer: XValuePresentation.XValueTextRenderer) {
    renderer.renderSpecialSymbol(className)
    renderer.renderSpecialSymbol("[")
    renderer.renderSpecialSymbol(length)
    renderer.renderSpecialSymbol("]")
  }
}
