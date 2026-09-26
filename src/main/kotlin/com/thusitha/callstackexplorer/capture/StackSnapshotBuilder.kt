package com.thusitha.callstackexplorer.capture

import com.intellij.debugger.engine.JavaStackFrame
import com.intellij.openapi.diagnostic.Logger
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.frame.*
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Builds a [SnapshotModel] from a paused [XDebugSession].
 *
 * The frame walk (class/method/file/line) is cheap and synchronous-ish; it is
 * safe to run directly on `sessionPaused()`. Variable resolution goes through
 * JDI round-trips and is asynchronous by platform design, so it is bridged
 * with a bounded [CountDownLatch] per frame/value — a slow or huge object
 * graph degrades to a truncated capture instead of hanging the pause thread.
 * See §5.1 of IMPLEMENTATION_PLAN.md.
 */
object StackSnapshotBuilder {

    private val LOG = Logger.getInstance(StackSnapshotBuilder::class.java)

    private const val DEFAULT_VARIABLE_TIMEOUT_MS = 2000L
    private const val DEFAULT_FRAME_LIST_TIMEOUT_MS = 3000L

    /**
     * Captures only the paused thread's frames (class/method/file/line), no
     * variables. Cheap enough to call synchronously from `sessionPaused()`.
     */
    fun captureSkeleton(session: XDebugSession, label: String): SnapshotModel? {
        val executionStack = session.suspendContext?.activeExecutionStack ?: run {
            LOG.warn("No active execution stack at pause time")
            return null
        }
        val frames = fetchStackFrames(executionStack, DEFAULT_FRAME_LIST_TIMEOUT_MS) ?: return null
        val frameSnapshots = frames.mapNotNull { toFrameSnapshot(it, includeVariables = false, maxDepth = 0, timeoutMs = 0) }
        val threadSnapshot = ThreadSnapshot(
            threadName = executionStack.displayName,
            frames = frameSnapshots
        )
        return SnapshotModel(
            id = UUID.randomUUID().toString(),
            capturedAt = Instant.now(),
            label = label,
            threads = listOf(threadSnapshot)
        )
    }

    /**
     * Full capture including variables, up to [maxDepth] levels of children.
     * Intended to be called off the pause thread (background task/coroutine) -
     * this method blocks the calling thread while it bridges async platform
     * callbacks, so never call it directly from `sessionPaused()`.
     */
    fun captureFull(
        session: XDebugSession,
        label: String,
        maxDepth: Int,
        variableTimeoutMs: Long = DEFAULT_VARIABLE_TIMEOUT_MS
    ): SnapshotModel? {
        val executionStack = session.suspendContext?.activeExecutionStack ?: run {
            LOG.warn("No active execution stack at pause time")
            return null
        }
        val frames = fetchStackFrames(executionStack, DEFAULT_FRAME_LIST_TIMEOUT_MS) ?: return null
        val frameSnapshots = frames.mapNotNull {
            toFrameSnapshot(it, includeVariables = true, maxDepth = maxDepth, timeoutMs = variableTimeoutMs)
        }
        val threadSnapshot = ThreadSnapshot(
            threadName = executionStack.displayName,
            frames = frameSnapshots
        )
        return SnapshotModel(
            id = UUID.randomUUID().toString(),
            capturedAt = Instant.now(),
            label = label,
            threads = listOf(threadSnapshot)
        )
    }

    // ---- frame listing ----------------------------------------------------

    private fun fetchStackFrames(executionStack: XExecutionStack, timeoutMs: Long): List<XStackFrame>? {
        val latch = CountDownLatch(1)
        val collected = mutableListOf<XStackFrame>()
        var errorMessage: String? = null

        executionStack.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
            override fun addStackFrames(stackFrames: MutableList<out XStackFrame>, last: Boolean) {
                collected.addAll(stackFrames)
                if (last) latch.countDown()
            }

            override fun errorOccurred(errorMessage2: String) {
                errorMessage = errorMessage2
                latch.countDown()
            }

            override fun isObsolete(): Boolean = false
        })

        val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) {
            LOG.warn("Timed out after ${timeoutMs}ms waiting for stack frames; captured ${collected.size} so far")
        }
        errorMessage?.let { LOG.warn("Error computing stack frames: $it") }
        return if (collected.isEmpty() && errorMessage != null) null else collected
    }

    // ---- per-frame conversion ----------------------------------------------

    private fun toFrameSnapshot(
        frame: XStackFrame,
        includeVariables: Boolean,
        maxDepth: Int,
        timeoutMs: Long
    ): FrameSnapshot? {
        val sourcePosition: XSourcePosition? = frame.sourcePosition
        val fileUrl = sourcePosition?.file?.url
        val lineNumber = (sourcePosition?.line ?: -1) + 1 // stored 1-based for display

        val (className, methodName) = if (frame is JavaStackFrame) {
            try {
                val location = frame.stackFrameProxy.location()
                location.declaringType().name() to location.method().name()
            } catch (e: Exception) {
                LOG.warn("Failed to resolve JDI location for frame", e)
                "<unknown>" to "<unknown>"
            }
        } else {
            (sourcePosition?.file?.name ?: "<unknown>") to "<unknown>"
        }

        val variables = if (includeVariables && maxDepth > 0) {
            fetchVariables(frame, maxDepth, timeoutMs)
        } else {
            emptyList()
        }

        return FrameSnapshot(
            className = className,
            methodName = methodName,
            fileUrl = fileUrl,
            lineNumber = lineNumber,
            variables = variables
        )
    }

    // ---- variable resolution ------------------------------------------------

    private fun fetchVariables(frame: XStackFrame, maxDepth: Int, timeoutMs: Long): List<VariableSnapshot> {
        val latch = CountDownLatch(1)
        val children = mutableListOf<Pair<String, XValue>>()

        frame.computeChildren(object : XCompositeNode {
            override fun addChildren(children2: XValueChildrenList, last: Boolean) {
                for (i in 0 until children2.size()) {
                    children.add(children2.getName(i) to children2.getValue(i))
                }
                if (last) latch.countDown()
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun tooManyChildren(remaining: Int) {
                latch.countDown()
            }

            override fun setErrorMessage(errorMessage: String) {
                LOG.warn("Error computing frame children: $errorMessage")
                latch.countDown()
            }

            override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                LOG.warn("Error computing frame children: $errorMessage")
                latch.countDown()
            }

            override fun setMessage(
                message: String,
                icon: javax.swing.Icon?,
                attributes: com.intellij.ui.SimpleTextAttributes,
                link: XDebuggerTreeNodeHyperlink?
            ) {
                // informational only, not a value - ignore for capture purposes
            }

            override fun setAlreadySorted(alreadySorted: Boolean) {
                // ordering doesn't matter for a point-in-time capture
            }

            override fun isObsolete(): Boolean = false
        })

        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return children.map { (name, value) -> resolveVariable(name, value, depthRemaining = maxDepth - 1, timeoutMs = timeoutMs) }
    }

    private fun resolveVariable(name: String, value: XValue, depthRemaining: Int, timeoutMs: Long): VariableSnapshot {
        val latch = CountDownLatch(1)
        var typeText = ""
        var valueText = ""

        value.computePresentation(object : XValueNode {
            override fun setPresentation(icon: javax.swing.Icon?, type: String?, value: String, hasChildren: Boolean) {
                typeText = type ?: ""
                valueText = value
                latch.countDown()
            }

            override fun setPresentation(icon: javax.swing.Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                typeText = presentation.type ?: ""
                val renderer = PlainTextValueRenderer()
                presentation.renderValue(renderer)
                valueText = renderer.text()
                latch.countDown()
            }

            override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {
                // capture keeps the (possibly truncated) short preview only
            }

            override fun isObsolete(): Boolean = false
        }, XValuePlace.TREE)

        latch.await(timeoutMs, TimeUnit.MILLISECONDS)

        val children = if (depthRemaining > 0) {
            fetchChildrenOf(value, depthRemaining, timeoutMs)
        } else {
            emptyList()
        }

        return VariableSnapshot(name = name, type = typeText, valuePreview = valueText, children = children)
    }

    private fun fetchChildrenOf(value: XValue, depthRemaining: Int, timeoutMs: Long): List<VariableSnapshot> {
        val latch = CountDownLatch(1)
        val children = mutableListOf<Pair<String, XValue>>()

        value.computeChildren(object : XCompositeNode {
            override fun addChildren(children2: XValueChildrenList, last: Boolean) {
                for (i in 0 until children2.size()) {
                    children.add(children2.getName(i) to children2.getValue(i))
                }
                if (last) latch.countDown()
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun tooManyChildren(remaining: Int) {
                latch.countDown()
            }

            override fun setErrorMessage(errorMessage: String) {
                latch.countDown()
            }

            override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                latch.countDown()
            }

            override fun setMessage(
                message: String,
                icon: javax.swing.Icon?,
                attributes: com.intellij.ui.SimpleTextAttributes,
                link: XDebuggerTreeNodeHyperlink?
            ) {
            }

            override fun setAlreadySorted(alreadySorted: Boolean) {
            }

            override fun isObsolete(): Boolean = false
        })

        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return children.map { (name, v) -> resolveVariable(name, v, depthRemaining - 1, timeoutMs) }
    }
}

/** Flattens an [XValuePresentation]'s rendering calls into a plain string for storage. */
private class PlainTextValueRenderer : XValuePresentation.XValueTextRenderer {
    private val sb = StringBuilder()

    fun text(): String = sb.toString()

    override fun renderValue(value: String) {
        sb.append(value)
    }

    override fun renderValue(value: String, key: com.intellij.openapi.editor.colors.TextAttributesKey) {
        sb.append(value)
    }

    override fun renderStringValue(value: String) {
        sb.append(value)
    }

    override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) {
        sb.append(value)
    }

    override fun renderNumericValue(value: String) {
        sb.append(value)
    }

    override fun renderKeywordValue(value: String) {
        sb.append(value)
    }

    override fun renderComment(comment: String) {
        sb.append(comment)
    }

    override fun renderSpecialSymbol(symbol: String) {
        sb.append(symbol)
    }

    override fun renderError(error: String) {
        sb.append(error)
    }
}
