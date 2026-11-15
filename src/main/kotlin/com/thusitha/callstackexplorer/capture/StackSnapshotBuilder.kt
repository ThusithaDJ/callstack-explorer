package com.thusitha.callstackexplorer.capture

import com.intellij.debugger.engine.DebuggerManagerThreadImpl
import com.intellij.debugger.engine.JavaDebugProcess
import com.intellij.debugger.engine.JavaStackFrame
import com.intellij.debugger.engine.events.DebuggerCommandImpl
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
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
 * Variable resolution goes through JDI round-trips and is asynchronous by
 * platform design, so it is bridged
 * with a bounded [CountDownLatch] per frame/value — a slow or huge object
 * graph degrades to a truncated capture instead of hanging the pause thread.
 * See §5.1 of IMPLEMENTATION_PLAN.md.
 */
object StackSnapshotBuilder {

    private val LOG = Logger.getInstance(StackSnapshotBuilder::class.java)

    private const val DEFAULT_VARIABLE_TIMEOUT_MS = 2000L
    private const val DEFAULT_FRAME_LIST_TIMEOUT_MS = 3000L

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
        variableTimeoutMs: Long = DEFAULT_VARIABLE_TIMEOUT_MS,
        indicator: ProgressIndicator? = null
    ): SnapshotModel? {
        val executionStack = session.suspendContext?.activeExecutionStack ?: run {
            LOG.warn("No active execution stack at pause time")
            return null
        }
        indicator?.text2 = "Listing stack frames..."
        val frames = fetchStackFrames(executionStack, DEFAULT_FRAME_LIST_TIMEOUT_MS) ?: return null
        val managerThread = managerThreadOf(session)
        indicator?.isIndeterminate = false
        val frameSnapshots = frames.mapIndexedNotNull { index, frame ->
            // Once cancelled, keep the cheap frame skeleton but stop the
            // expensive variable round-trips, so the user still gets a partial snapshot.
            val cancelled = indicator?.isCanceled == true
            if (!cancelled) {
                indicator?.fraction = index.toDouble() / frames.size.coerceAtLeast(1)
                indicator?.text2 = "Reading variables of frame ${index + 1} of ${frames.size}"
            }
            toFrameSnapshot(
                frame, managerThread,
                includeVariables = !cancelled, maxDepth = maxDepth, timeoutMs = variableTimeoutMs
            )
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

    /**
     * Captures only [values] (typically the nodes selected in the debugger's
     * Variables view), each resolved [maxDepth] levels deep, tagged with the
     * current frame's location so the snapshot can be navigated and compared
     * against the same variables in a later run.
     */
    fun captureValues(
        session: XDebugSession,
        label: String,
        values: List<Pair<String, XValue>>,
        maxDepth: Int,
        variableTimeoutMs: Long = DEFAULT_VARIABLE_TIMEOUT_MS,
        indicator: ProgressIndicator? = null
    ): SnapshotModel {
        indicator?.isIndeterminate = false
        val variables = values.mapIndexed { index, (name, value) ->
            indicator?.fraction = index.toDouble() / values.size.coerceAtLeast(1)
            indicator?.text2 = "Reading '$name' (${index + 1} of ${values.size})"
            resolveVariable(name, value, depthRemaining = maxDepth - 1, timeoutMs = variableTimeoutMs)
        }
        return valuesSnapshot(session, label, variables)
    }

    /**
     * Looks up the top-level variables called [names] in the current frame and
     * resolves them [maxDepth] levels deep. Used to compare a saved
     * [SnapshotKind.VALUES] snapshot with the same variables in the live session.
     */
    fun captureNamedVariables(
        session: XDebugSession,
        label: String,
        names: Collection<String>,
        maxDepth: Int,
        variableTimeoutMs: Long = DEFAULT_VARIABLE_TIMEOUT_MS,
        indicator: ProgressIndicator? = null
    ): SnapshotModel? {
        val frame = session.currentStackFrame ?: return null
        indicator?.text2 = "Listing variables of the current frame..."
        val wanted = names.toSet()
        val found = listChildren(frame, variableTimeoutMs).filter { it.first in wanted }
        return captureValues(session, label, found, maxDepth, variableTimeoutMs, indicator)
    }

    private fun valuesSnapshot(session: XDebugSession, label: String, variables: List<VariableSnapshot>): SnapshotModel {
        val frame = session.currentStackFrame
        val position = frame?.sourcePosition
        val (className, methodName) = if (frame is JavaStackFrame) {
            managerThreadOf(session)?.let { resolveJdiLocation(frame, it, position) }
                ?: ((position?.file?.name ?: "<unknown>") to "<unknown>")
        } else {
            (position?.file?.name ?: "<unknown>") to "<unknown>"
        }
        val frameSnapshot = FrameSnapshot(
            className = className,
            methodName = methodName,
            fileUrl = position?.file?.url,
            lineNumber = (position?.line ?: -1) + 1,
            variables = variables
        )
        val threadName = session.suspendContext?.activeExecutionStack?.displayName ?: "<thread>"
        return SnapshotModel(
            id = UUID.randomUUID().toString(),
            capturedAt = Instant.now(),
            label = label,
            threads = listOf(ThreadSnapshot(threadName, listOf(frameSnapshot))),
            kind = SnapshotKind.VALUES
        )
    }

    // ---- frame listing ----------------------------------------------------

    private fun fetchStackFrames(executionStack: XExecutionStack, timeoutMs: Long): List<XStackFrame>? {
        val latch = CountDownLatch(1)
        // Callbacks arrive on the debugger thread and may outlive the timeout, so guard the list.
        val collected = java.util.Collections.synchronizedList(mutableListOf<XStackFrame>())
        val errorMessage = java.util.concurrent.atomic.AtomicReference<String?>()

        executionStack.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
            override fun addStackFrames(stackFrames: MutableList<out XStackFrame>, last: Boolean) {
                collected.addAll(stackFrames)
                if (last) latch.countDown()
            }

            override fun errorOccurred(errorMessage2: String) {
                errorMessage.set(errorMessage2)
                latch.countDown()
            }

            override fun isObsolete(): Boolean = false
        })

        val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) {
            LOG.warn("Timed out after ${timeoutMs}ms waiting for stack frames; captured ${collected.size} so far")
        }
        val error = errorMessage.get()
        error?.let { LOG.warn("Error computing stack frames: $it") }
        val frames = synchronized(collected) { collected.toList() }
        return if (frames.isEmpty() && error != null) null else frames
    }

    // ---- per-frame conversion ----------------------------------------------

    /**
     * Resolves the [DebuggerManagerThreadImpl] backing this session, if it's a
     * JVM debug session. Any call into raw JDI objects (like
     * [com.intellij.debugger.jdi.StackFrameProxyImpl.location]) must run on
     * this thread - the platform's own `XStackFrame`/`XValue` methods
     * (`computeChildren`, `computePresentation`) already marshal onto it
     * internally, but reaching into `JavaStackFrame.getStackFrameProxy()`
     * directly bypasses that, which is what §5.1 of IMPLEMENTATION_PLAN.md
     * flagged as the highest-risk part of this capture pipeline.
     */
    private fun managerThreadOf(session: XDebugSession): DebuggerManagerThreadImpl? {
        val javaProcess = session.debugProcess as? JavaDebugProcess ?: return null
        return javaProcess.debuggerSession.process.managerThread
    }

    private fun toFrameSnapshot(
        frame: XStackFrame,
        managerThread: DebuggerManagerThreadImpl?,
        includeVariables: Boolean,
        maxDepth: Int,
        timeoutMs: Long
    ): FrameSnapshot? {
        val sourcePosition: XSourcePosition? = frame.sourcePosition
        val fileUrl = sourcePosition?.file?.url
        val lineNumber = (sourcePosition?.line ?: -1) + 1 // stored 1-based for display

        val (className, methodName) = if (frame is JavaStackFrame && managerThread != null) {
            resolveJdiLocation(frame, managerThread, sourcePosition)
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

    /** Runs the raw JDI location lookup on the debugger's manager thread, as JDI requires. */
    private fun resolveJdiLocation(
        frame: JavaStackFrame,
        managerThread: DebuggerManagerThreadImpl,
        sourcePosition: XSourcePosition?
    ): Pair<String, String> {
        var className = "<unknown>"
        var methodName = "<unknown>"
        try {
            managerThread.invokeAndWait(object : DebuggerCommandImpl() {
                override fun action() {
                    val location = frame.stackFrameProxy.location()
                    className = location.declaringType().name()
                    methodName = location.method().name()
                }
            })
        } catch (e: Exception) {
            // Cancellation (ProcessCanceledException) must propagate, never be logged - IntelliJ reports that as an error.
            if (e is ControlFlowException) throw e
            LOG.warn("Failed to resolve JDI location for frame", e)
            className = sourcePosition?.file?.name ?: "<unknown>"
        }
        return className to methodName
    }

    // ---- variable resolution ------------------------------------------------

    private fun fetchVariables(frame: XStackFrame, maxDepth: Int, timeoutMs: Long): List<VariableSnapshot> =
        listChildren(frame, timeoutMs).map { (name, value) ->
            resolveVariable(name, value, depthRemaining = maxDepth - 1, timeoutMs = timeoutMs)
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
            listChildren(value, timeoutMs).map { (childName, child) ->
                resolveVariable(childName, child, depthRemaining - 1, timeoutMs)
            }
        } else {
            emptyList()
        }

        return VariableSnapshot(name = name, type = typeText, valuePreview = valueText, children = children)
    }

    /** Bridges the async `computeChildren` callback of a frame or value into a bounded blocking call. */
    private fun listChildren(container: XValueContainer, timeoutMs: Long): List<Pair<String, XValue>> {
        val latch = CountDownLatch(1)
        // Callbacks arrive on the debugger thread and may outlive the timeout, so guard the list.
        val children = java.util.Collections.synchronizedList(mutableListOf<Pair<String, XValue>>())

        container.computeChildren(object : XCompositeNode {
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
                LOG.debug("Error computing children: $errorMessage")
                latch.countDown()
            }

            override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                LOG.debug("Error computing children: $errorMessage")
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
        return synchronized(children) { children.toList() }
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
