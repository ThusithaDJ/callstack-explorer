package com.thusitha.callstackexplorer.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.impl.ui.XDebuggerUIConstants
import javax.swing.Icon

/**
 * Renders stored values the way the debugger's Variables view does:
 * `name = {Type@123} value`, with the debugger's own name/type/changed-value
 * colours and the editor's string/number/keyword colours.
 */
object DebuggerStyle {

    private val PRIMITIVES = setOf("int", "long", "short", "byte", "char", "boolean", "float", "double")
    private val NUMBER = Regex("""-?\d[\d_]*(\.\d+)?([eE][-+]?\d+)?[lLfFdD]?""")
    private val KEYWORDS = setOf("null", "true", "false")

    val nameAttributes: SimpleTextAttributes get() = XDebuggerUIConstants.VALUE_NAME_ATTRIBUTES
    val typeAttributes: SimpleTextAttributes get() = XDebuggerUIConstants.TYPE_ATTRIBUTES

    /** Blue, as the debugger shows values that changed since the previous step. */
    val changedAttributes: SimpleTextAttributes get() = XDebuggerUIConstants.CHANGED_VALUE_ATTRIBUTES

    val addedAttributes: SimpleTextAttributes
        get() = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, FileStatus.ADDED.color)

    val removedAttributes: SimpleTextAttributes
        get() = SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, FileStatus.DELETED.color)

    val oldValueAttributes: SimpleTextAttributes
        get() = SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor)

    fun iconFor(name: String, type: String, hasChildren: Boolean, isTopLevel: Boolean): Icon = when {
        type in PRIMITIVES -> AllIcons.Debugger.Db_primitive
        type.contains("[]") -> AllIcons.Debugger.Db_array
        !isTopLevel && !name.startsWith("[") -> AllIcons.Nodes.Field
        hasChildren || type.isNotEmpty() -> AllIcons.Debugger.Value
        else -> AllIcons.Debugger.Db_primitive
    }

    /** Appends `name = {type} value`; [valueAttributes] overrides syntax colouring (e.g. for changed values). */
    fun append(
        target: SimpleColoredComponent,
        name: String,
        type: String?,
        value: String?,
        nameAttributes: SimpleTextAttributes = this.nameAttributes,
        valueAttributes: SimpleTextAttributes? = null
    ) {
        target.append(name, nameAttributes)
        target.append(" = ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        if (!type.isNullOrEmpty()) target.append("{$type} ", valueAttributes?.let { typeLike(it) } ?: typeAttributes)
        appendValue(target, value.orEmpty(), valueAttributes)
    }

    fun appendValue(target: SimpleColoredComponent, value: String, override: SimpleTextAttributes? = null) {
        val singleLine = value.replace('\n', ' ')
        target.append(singleLine, override ?: syntaxAttributes(singleLine))
    }

    private fun typeLike(attrs: SimpleTextAttributes): SimpleTextAttributes =
        if (attrs.style and SimpleTextAttributes.STYLE_STRIKEOUT != 0) attrs else typeAttributes

    private fun syntaxAttributes(value: String): SimpleTextAttributes {
        val key: TextAttributesKey = when {
            value.startsWith("\"") || value.startsWith("'") -> DefaultLanguageHighlighterColors.STRING
            value in KEYWORDS -> DefaultLanguageHighlighterColors.KEYWORD
            NUMBER.matches(value) -> DefaultLanguageHighlighterColors.NUMBER
            else -> return SimpleTextAttributes.REGULAR_ATTRIBUTES
        }
        val attrs = EditorColorsManager.getInstance().globalScheme.getAttributes(key)
            ?: return SimpleTextAttributes.REGULAR_ATTRIBUTES
        return SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, attrs.foregroundColor)
    }
}
