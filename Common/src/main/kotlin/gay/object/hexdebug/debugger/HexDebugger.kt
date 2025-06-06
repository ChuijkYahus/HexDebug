package gay.`object`.hexdebug.debugger

import at.petrak.hexcasting.api.HexAPI
import at.petrak.hexcasting.api.casting.SpellList
import at.petrak.hexcasting.api.casting.eval.*
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect
import at.petrak.hexcasting.api.casting.eval.vm.*
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation.Done
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation.NotDone
import at.petrak.hexcasting.api.casting.iota.*
import at.petrak.hexcasting.api.casting.mishaps.Mishap
import at.petrak.hexcasting.api.casting.mishaps.MishapInternalException
import at.petrak.hexcasting.api.casting.mishaps.MishapStackSize
import at.petrak.hexcasting.common.casting.actions.eval.OpEval
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import gay.`object`.hexdebug.adapter.LaunchArgs
import gay.`object`.hexdebug.casting.eval.FrameBreakpoint
import gay.`object`.hexdebug.casting.eval.IDebugCastEnv
import gay.`object`.hexdebug.casting.eval.debugCastEnv
import gay.`object`.hexdebug.casting.iotas.CognitohazardIota
import gay.`object`.hexdebug.debugger.allocators.SourceAllocator
import gay.`object`.hexdebug.debugger.allocators.VariablesAllocator
import gay.`object`.hexdebug.utils.ceilToPow
import gay.`object`.hexdebug.utils.displayWithPatternName
import gay.`object`.hexdebug.utils.toHexpatternSource
import net.minecraft.server.level.ServerLevel
import org.eclipse.lsp4j.debug.*
import java.util.*
import kotlin.math.min
import org.eclipse.lsp4j.debug.LoadedSourceEventArgumentsReason as LoadedSourceReason

class HexDebugger(
    var initArgs: InitializeRequestArguments,
    var launchArgs: LaunchArgs,
    private val defaultEnv: CastingEnvironment,
    private val world: ServerLevel,
    private val onExecute: ((Iota) -> Unit)? = null,
    iotas: List<Iota>,
    private var image: CastingImage = CastingImage(),
) {
    constructor(
        initArgs: InitializeRequestArguments,
        launchArgs: LaunchArgs,
        castArgs: CastArgs,
        image: CastingImage = CastingImage(),
    ) : this(initArgs, launchArgs, castArgs.env, castArgs.world, castArgs.onExecute, castArgs.iotas, image)

    var lastEvaluatedMetadata: IotaMetadata? = null
        private set

    // ensure we passed a debug cast env to help catch errors early
    init {
        defaultEnv as IDebugCastEnv
    }

    private val variablesAllocator = VariablesAllocator()
    private val sourceAllocator = SourceAllocator(iotas.hashCode())

    private val iotaMetadata = IdentityHashMap<Iota, IotaMetadata>()
    // FIXME: this is really terrible and gross and i don't like it
    private val frameInvocationMetadata = IdentityHashMap<SpellContinuation, () -> Pair<Iota, IotaMetadata?>?>()
    private val virtualFrames = IdentityHashMap<SpellContinuation, MutableList<StackFrame>>()

    private val breakpoints = mutableMapOf<Int, MutableMap<Int, SourceBreakpointMode>>() // source id -> line number
    private val exceptionBreakpoints = mutableSetOf<ExceptionBreakpointType>()

    // this gets set to true by registerNewSource if a frame is loaded that contains a cognitohazard iota
    private var isPoisoned = false

    private val initialSource = registerNewSource(iotas)!!

    private var callStack = listOf<NotDone>()

    val evaluatorUIPatterns = mutableListOf<ResolvedPattern>()

    private var evaluatorResetData: EvaluatorResetData? = null

    private var isAtCaughtMishap = false

    private var lastResolutionType = ResolvedPatternType.UNRESOLVED

    // Initialize the continuation stack to a single top-level eval for all iotas.
    private var nextContinuation: SpellContinuation = Done
        set(value) {
            field = value
            callStack = getCallStack(value)
        }

    init {
        nextContinuation = nextContinuation
            .run {
                if (launchArgs.stopOnExit) {
                    // FIXME: scuffed as hell
                    val lastIota = iotas.lastOrNull()
                    val columnIndex = lastIota?.let {
                        // +1 so it goes *after* the last character
                        iotaToString(it, isSource = true).lastIndex + 1
                    }
                    pushFrame(FrameBreakpoint(stopBefore = true)).also { newCont ->
                        frameInvocationMetadata[newCont] = {
                            lastIota?.let { it to iotaMetadata[it]?.copy(columnIndex = columnIndex) }
                        }
                    }
                } else this
            }
            .pushFrame(FrameEvaluate(SpellList.LList(0, iotas), false))
    }

    private val nextFrame get() = (nextContinuation as? NotDone)?.frame

    private fun getVM(env: CastingEnvironment? = null) = CastingVM(image, env ?: defaultEnv)

    private fun registerNewSource(frame: ContinuationFrame): Source? = getIotas(frame)?.let(::registerNewSource)

    private fun registerNewSource(iotas: Iterable<Iota>): Source? {
        val unregisteredIotas = iotas.filter { it !in iotaMetadata }
        if (unregisteredIotas.isEmpty()) return null

        val source = sourceAllocator.add(unregisteredIotas)
        for ((index, iota) in unregisteredIotas.withIndex()) {
            if (iota is CognitohazardIota) {
                isPoisoned = true
            }
            iotaMetadata[iota] = IotaMetadata(source, index)
        }
        return source
    }

    private fun getIotas(frame: ContinuationFrame) = when (frame) {
        is FrameEvaluate -> frame.list
        is FrameForEach -> frame.code
        else -> null
    }

    private fun getFirstIotaMetadata(continuation: NotDone): Pair<Iota, IotaMetadata?>? =
        // for FrameEvaluate, show the next iota to be evaluated (if any)
        (continuation.frame as? FrameEvaluate)?.let(::getFirstIotaMetadata)
        // for everything else, show the caller if we have it
        ?: frameInvocationMetadata[continuation]?.invoke()
        // otherwise show the first contained iota
        ?: getFirstIotaMetadata(continuation.frame)

    private fun getFirstIotaMetadata(frame: ContinuationFrame) = getIotas(frame)?.let { it.car to iotaMetadata[it.car] }

    // current continuation is last
    private fun getCallStack(current: SpellContinuation) = generateSequence(current as? NotDone) {
        when (val next = it.next) {
            is Done -> null
            is NotDone -> next
        }
    }.toList().asReversed()

    /** (iota, index) */
    fun getNextIotaToEvaluate(): Pair<String, Int>? {
        val continuation = nextContinuation as? NotDone ?: return null
        val (iota, meta) = getFirstIotaMetadata(continuation) ?: return null
        return iotaToString(iota, isSource = false) to (meta?.lineIndex ?: -1)
    }

    fun getStackFrames(): Sequence<StackFrame> {
        var frameId = 1
        var virtualFrameId = (callStack.size + 1).ceilToPow(10)
        return callStack.flatMap { continuation ->
            listOf(
                StackFrame().apply {
                    id = frameId++
                    name = "[$id] ${continuation.frame.name}"
                    setSourceAndPosition(initArgs, getFirstIotaMetadata(continuation)?.second)
                }
            ) + virtualFrames[continuation]?.map {
                it.apply {
                    id = virtualFrameId++
                    presentationHint = StackFramePresentationHint.SUBTLE
                }
            }.orEmpty()
        }.asReversed().asSequence()
    }

    fun getScopes(frameId: Int): List<Scope> {
        val scopes = mutableListOf(
            Scope().apply {
                name = "Data"
                variablesReference = image.run {
                    variablesAllocator.add(
                        toVariable("Stack", stack.asReversed()),
                        toVariable("Ravenmind", getRavenmind()),
                    )
                }
            },
            Scope().apply {
                name = "State"
                variablesReference = image.run {
                    val variables = mutableListOf(
                        toVariable("OpsConsumed", opsConsumed.toString()),
                        toVariable("EscapeNext", escapeNext.toString()),
                        toVariable("ParenCount", parenCount.toString()),
                    )
                    if (parenCount > 0) {
                        variables += toVariable("Parenthesized", parenthesized.map { it.iota })
                    }
                    variablesAllocator.add(variables)
                }
            },
        )

        // virtual frames are given ids past the end of the call stack; just ignore them here
        getContinuation(frameId)?.also { continuation ->
            getFrameVariables(continuation)?.also {
                scopes += Scope().apply {
                    name = "Frame"
                    variablesReference = variablesAllocator.add(it)
                }
            }
        }

        return scopes
    }

    fun getVariables(reference: Int) = variablesAllocator.getOrEmpty(reference)

    private fun getContinuationVariable(
        continuation: SpellContinuation,
        variableName: String = "",
    ): Variable = Variable().apply {
        name = variableName
        when (continuation) {
            is Done -> value = "Done"
            is NotDone -> {
                value = "NotDone"
                variablesReference = variablesAllocator.add(
                    Variable().apply {
                        name = "Frame"
                        value = continuation.frame.name
                        getFrameVariables(continuation)?.let {
                            variablesReference = variablesAllocator.add(it)
                        }
                    },
                    getContinuationVariable(continuation.next, "Next"),
                )
            }
        }
    }

    private fun getFrameVariables(continuation: NotDone): Sequence<Variable>? {
        val sourceLine = getFirstIotaMetadata(continuation)?.toString() ?: ""
        return when (val frame = continuation.frame) {
            is FrameEvaluate -> sequenceOf(
                toVariable("Code", frame.list, sourceLine),
                toVariable("IsMetacasting", frame.isMetacasting.toString()),
            )

            is FrameForEach -> sequenceOf(
                toVariable("Code", frame.code, sourceLine),
                toVariable("Data", frame.data),
                frame.baseStack?.let { toVariable("BaseStack", it) },
                toVariable("Result", frame.acc),
            ).filterNotNull()

            is FrameBreakpoint -> sequenceOf(
                toVariable("StopBefore", frame.stopBefore.toString()),
                toVariable("IsFatal", frame.isFatal.toString()),
            )

            else -> if (sourceLine.isNotEmpty()) sequenceOf(
                toVariable("Code", sourceLine),
            ) else null
        }
    }

    private fun getRavenmind() = image.userData.let {
        if (it.contains(HexAPI.RAVENMIND_USERDATA)) {
            IotaType.deserialize(it.getCompound(HexAPI.RAVENMIND_USERDATA), defaultEnv.world)
        } else {
            NullIota()
        }
    }

    private fun toVariables(iotas: Iterable<Iota>) = toVariables(iotas.asSequence())

    private fun toVariables(iotas: Sequence<Iota>) = iotas.mapIndexed(::toVariable)

    private fun toVariable(index: Number, iota: Iota) = toVariable("$index", iota)

    private fun toVariable(variableName: String, iota: Iota): Variable = Variable().apply {
        name = variableName
        type = iota::class.simpleName
        when (iota) {
            is ListIota -> {
                value = "(${iota.list.count()}) ${iotaToString(iota)}"
                variablesReference = allocateVariables(iota.list)
                indexedVariables = iota.list.size()
            }

            is ContinuationIota -> getContinuationVariable(iota.continuation).also {
                value = "${iotaToString(iota)} -> ${it.value}"
                variablesReference = it.variablesReference
            }

            else -> value = iotaToString(iota)
        }
    }

    private fun toVariable(name: String, iotas: Iterable<Iota>, value: String = ""): Variable = Variable().also {
        it.name = name
        it.value = value
        it.variablesReference = allocateVariables(iotas)
    }

    private fun toVariable(name: String, value: String): Variable = Variable().also {
        it.name = name
        it.value = value
    }

    private fun allocateVariables(iotas: Iterable<Iota>) = variablesAllocator.add(toVariables(iotas))

    fun getSources() = sourceAllocator.map { it.first }

    fun getSourceContents(reference: Int): String? = sourceAllocator[reference]?.second?.let(::getSourceContents)

    private fun getSourceContents(iotas: Iterable<Iota>): String {
        return iotas.joinToString("\n") {
            val indent = iotaMetadata[it]?.indent(launchArgs.indentWidth) ?: ""
            indent + iotaToString(it, true)
        }
    }

    private fun getContinuation(frameId: Int) = callStack.elementAtOrNull(frameId - 1)

    // TODO: gross.
    // TODO: there's probably a bug here somewhere - shouldn't we be using the metadata?
    fun setBreakpoints(sourceReference: Int, sourceBreakpoints: Array<SourceBreakpoint>): List<Breakpoint> {
        val (source, iotas) = sourceAllocator[sourceReference] ?: (null to null)
        val breakpointLines = breakpoints.getOrPut(sourceReference, ::mutableMapOf).apply { clear() }
        return sourceBreakpoints.map {
            Breakpoint().apply {
                isVerified = false
                if (source == null || iotas == null) {
                    message = "Unknown source"
                    reason = BreakpointNotVerifiedReason.PENDING  // TODO: send Breakpoint event later
                } else if (it.line > initArgs.indexToLine(iotas.lastIndex)) {
                    message = "Line number out of range"
                    reason = BreakpointNotVerifiedReason.FAILED
                } else {
                    isVerified = true
                    this.source = source
                    line = it.line

                    breakpointLines[it.line] = it.mode
                        ?.let(SourceBreakpointMode::valueOf)
                        ?: SourceBreakpointMode.EVALUATED
                }
            }
        }
    }

    fun setExceptionBreakpoints(typeNames: Array<String>): List<Breakpoint> {
        exceptionBreakpoints.clear()
        return typeNames.map {
            exceptionBreakpoints.add(ExceptionBreakpointType.valueOf(it))
            Breakpoint().apply { isVerified = true }
        }
    }

    private fun isAtBreakpoint(): Boolean {
        val nextIota = when (val frame = nextFrame) {
            // why is this empty sometimes??????
            is FrameEvaluate -> getIotas(frame)?.firstOrNull()
            is FrameBreakpoint -> return true
            else -> null
        } ?: return false

        val breakpointMode = iotaMetadata[nextIota]
            ?.let { breakpoints[it.source.sourceReference]?.get(it.line(initArgs)) }
            ?: return false

        val escapeNext = image.escapeNext || image.parenCount > 0

        return when (breakpointMode) {
            SourceBreakpointMode.EVALUATED -> !escapeNext
            SourceBreakpointMode.ESCAPED -> escapeNext
            SourceBreakpointMode.ALL -> true
        }
    }

    fun generateDescs() = getVM().generateDescs()

    private fun getClientView(vm: CastingVM): ExecutionClientView {
        val (stackDescs, ravenmind) = vm.generateDescs()
        val isStackClear = nextContinuation is Done // only close the window if we're done evaluating
        return ExecutionClientView(isStackClear, lastResolutionType, stackDescs, ravenmind)
    }

    /**
     * Use [DebugAdapter.evaluate][gay.object.hexdebug.adapter.DebugAdapter.evaluate] instead.
     */
    internal fun evaluate(env: CastingEnvironment, list: SpellList): DebugStepResult {
        val vm = getVM(env)

        if (isAtCaughtMishap) {
            // manually trigger the mishap sound
            // TODO: this feels scuffed.
            env.postExecution(
                CastResult(NullIota(), nextContinuation, null, listOf(), lastResolutionType, HexEvalSounds.MISHAP)
            )
            return DebugStepResult(StopReason.EXCEPTION, clientInfo = getClientView(vm))
        }

        val startedEvaluating = evaluatorResetData == null
        if (startedEvaluating) {
            evaluatorResetData = EvaluatorResetData(nextContinuation, image, lastResolutionType, isAtCaughtMishap)
        }

        nextContinuation = nextContinuation.pushFrame(FrameEvaluate(list, false))
        return executeNextDebugStep(vm, doStaffMishaps = true).copy(startedEvaluating = startedEvaluating)
    }

    /**
     * Use [DebugAdapter.resetEvaluator][gay.object.hexdebug.adapter.DebugAdapter.resetEvaluator] instead.
     */
    internal fun resetEvaluator() = (evaluatorResetData != null).also { _ ->
        evaluatorResetData?.also {
            nextContinuation = it.continuation
            image = it.image
            lastResolutionType = it.lastResolutionType
            isAtCaughtMishap = it.isAtCaughtMishap
        }
        evaluatorResetData = null
        evaluatorUIPatterns.clear()
    }

    fun start(): DebugStepResult {
        return if (launchArgs.stopOnEntry) {
            DebugStepResult(StopReason.STARTED)
        } else if (isAtBreakpoint()) {
            DebugStepResult(StopReason.BREAKPOINT)
        } else {
            executeUntilStopped()
        }.withLoadedSource(initialSource, LoadedSourceReason.NEW)
    }

    fun executeUntilStopped(stepType: RequestStepType? = null): DebugStepResult {
        val vm = getVM()
        var lastResult: DebugStepResult? = null
        var isEscaping: Boolean? = null
        var stepDepth = 0
        var shouldStop = false
        var hitBreakpoint = false

        while (true) {
            var result = executeNextDebugStep(vm, exactlyOnce = true)
            if (lastResult != null) result += lastResult
            lastResult = result

            if (result.reason.stopImmediately) return result

            if (isAtBreakpoint()) {
                hitBreakpoint = true
            }

            if (hitBreakpoint && shouldStopAtFrame(nextContinuation)) {
                return result.copy(reason = StopReason.BREAKPOINT)
            }

            // if stepType is null, we should ONLY stop on breakpoints
            if (stepType == null) continue

            // alwinfy says: "beware Iris very much"
            if (result.type == DebugStepType.JUMP) {
                shouldStop = true
            }

            stepDepth += when (result.type) {
                DebugStepType.IN -> 1
                DebugStepType.OUT -> -1
                else -> 0
            }

            if (isEscaping == null) {
                isEscaping = result.type == DebugStepType.ESCAPE
            }

            shouldStop = shouldStop || if (isEscaping) {
                result.type != DebugStepType.ESCAPE
            } else when (stepType) {
                RequestStepType.OVER ->  stepDepth <= 0
                RequestStepType.OUT -> stepDepth < 0
            }

            if (shouldStop && shouldStopAtFrame(nextContinuation)) {
                return result
            }
        }
    }

    fun executeOnce() = executeNextDebugStep(getVM())

    /**
     * Copy of [CastingVM.queueExecuteAndWrapIotas] to allow stepping by one pattern at a time.
     */
    private fun executeNextDebugStep(
        vm: CastingVM,
        exactlyOnce: Boolean = false,
        doStaffMishaps: Boolean = false,
    ): DebugStepResult {
        var stepResult = DebugStepResult(StopReason.STEP)

        var continuation = nextContinuation // bind locally so we can do smart casting
        if (continuation !is NotDone) return stepResult.done()

        variablesAllocator.clear()

        // Begin aggregating info
        val info = CastingVM.TempControllerInfo(earlyExit = false)
        while (continuation is NotDone && !info.earlyExit) {
            vm.debugCastEnv.reset()

            // Take the top of the continuation stack...
            val frame = continuation.frame

            // TODO: there's probably a less hacky way to do this
            if (frame is FrameBreakpoint && frame.isFatal || isPoisoned) {
                continuation = Done
                break
            }

            // ...and execute it.
            val castResult = frame.evaluate(continuation.next, world, vm).let { result ->
                // if stack is unable to be serialized, have the result be an error
                val newData = result.newData
                if (newData != null && IotaType.isTooLargeToSerialize(newData.stack)) {
                    result.copy(
                        newData = null,
                        sideEffects = listOf(OperatorSideEffect.DoMishap(MishapStackSize(), Mishap.Context(null, null))),
                        resolutionType = ResolvedPatternType.ERRORED,
                        sound = HexEvalSounds.MISHAP,
                    )
                } else {
                    result
                }
            }

            val newImage = castResult.newData

            // if something went wrong, push a breakpoint or stop immediately
            // and save the old image so we can see the stack BEFORE the mishap instead of after
            // TODO: maybe implement a way to see the stack at each call frame instead?
            val (newContinuation, preMishapImage) = if (castResult.resolutionType.success || doStaffMishaps) {
                Pair(castResult.continuation, null)
            } else if (ExceptionBreakpointType.UNCAUGHT_MISHAPS in exceptionBreakpoints) {
                isAtCaughtMishap = true
                stepResult = stepResult.copy(reason = StopReason.EXCEPTION)
                Pair(castResult.continuation.pushFrame(FrameBreakpoint.fatal()), vm.image)
            } else {
                Pair(Done, null)
            }

            // we use this when printing to the console, so this should happen BEFORE vm.env.postExecution (ie. for mishaps)
            lastEvaluatedMetadata = iotaMetadata[castResult.cast]

            // Then write all pertinent data back to the harness for the next iteration.
            if (newImage != null) {
                stepResult = handleIndent(castResult, vm.image, newImage, stepResult)
                vm.image = newImage
            }
            vm.env.postExecution(castResult)

            if (castResult.resolutionType == ResolvedPatternType.EVALUATED) {
                onExecute?.invoke(castResult.cast)
            }

            val stepType = getStepType(vm, castResult, continuation, newContinuation)
            if (newContinuation is NotDone) {
                setIotaOverrides(castResult, continuation, newContinuation, stepType)

                // ensure all of the iotas to be evaluated in the next frame are mapped to a source
                registerNewSource(newContinuation.frame)?.also {
                    stepResult = stepResult.withLoadedSource(it, LoadedSourceReason.NEW)
                }

                // insert a virtual FrameFinishEval if OpEval didn't (ie. if we did a TCO)
                if (launchArgs.showTailCallFrames && vm.debugCastEnv.lastEvaluatedAction is OpEval) {
                    val invokeMeta = iotaMetadata[castResult.cast]
                    val nextInvokeMeta = frameInvocationMetadata[newContinuation.next]?.invoke()?.second
                    if (invokeMeta != null && invokeMeta != nextInvokeMeta) {
                        virtualFrames.getOrPut(continuation.next) { mutableListOf() }.add(
                            StackFrame().apply {
                                name = "FrameFinishEval"
                                setSourceAndPosition(initArgs, invokeMeta)
                            }
                        )
                    }
                }
            }
            if (stepType != null) {
                stepResult = stepResult.copy(type = stepType)
            }

            continuation = newContinuation
            lastResolutionType = castResult.resolutionType

            try {
                vm.performSideEffects(castResult.sideEffects)
            } catch (e: Exception) {
                e.printStackTrace()
                vm.performSideEffects(
                    listOf(OperatorSideEffect.DoMishap(MishapInternalException(e), Mishap.Context(null, null)))
                )
            }
            info.earlyExit = info.earlyExit || !castResult.resolutionType.success

            // this needs to be after performSideEffects, since that's where mishaps mess with the stack
            // note: preMishapImage is only non-null if we actually encountered a mishap
            if (preMishapImage != null) vm.image = preMishapImage

            if (exactlyOnce || shouldStopAtFrame(continuation)) {
                break
            }
        }

        // never show virtual frames above the top of the call stack
        virtualFrames[continuation]?.clear()

        nextContinuation = continuation
        image = vm.image

        return when (continuation) {
            is Done -> stepResult.done()
            is NotDone -> stepResult
        }.copy(clientInfo = getClientView(vm))
    }

    private fun shouldStopAtFrame(continuation: SpellContinuation) =
        continuation !is NotDone || shouldStopAtFrame(continuation.frame)

    /**
     * Returns false if this frame is "internal", ie. it normally wouldn't be helpful to pause the debugger when
     * we reach it. Always returns true if `skipNonEvalFrames` is disabled. Does not take breakpoints into account.
     */
    private fun shouldStopAtFrame(frame: ContinuationFrame): Boolean {
        if (!launchArgs.skipNonEvalFrames) return true
        return when (frame) {
            is FrameEvaluate -> true
            is FrameBreakpoint -> frame.stopBefore
            else -> false
        }
    }

    private fun handleIndent(
        castResult: CastResult,
        oldImage: CastingImage,
        newImage: CastingImage,
        stepResult: DebugStepResult,
    ) = when (castResult.resolutionType) {
        ResolvedPatternType.ESCAPED -> {
            // if the paren count changed, it was either an introspection or a retrospection
            // in both cases, the pattern that changed the indent level should be at the lower indent level
            val parenCount = min(oldImage.parenCount, newImage.parenCount)
            iotaMetadata[castResult.cast]?.trySetParenCount(parenCount)
            stepResult
        }

        ResolvedPatternType.EVALUATED -> if (newImage.parenCount == 0 && newImage.parenthesized.isEmpty() && oldImage.parenthesized.isNotEmpty()) {
            // closed list
            val sources = oldImage.parenthesized.asSequence().mapNotNull { iotaMetadata[it.iota] }
                .filter { it.needsReload.also { _ -> it.needsReload = false } }
                .associate { it.source to LoadedSourceReason.CHANGED }

            stepResult.withLoadedSources(sources)
        } else stepResult

        else -> stepResult
    }

    private fun getStepType(
        vm: CastingVM,
        castResult: CastResult,
        continuation: NotDone,
        newContinuation: SpellContinuation,
    ): DebugStepType? {
        val isEscaped = when (castResult.resolutionType) {
            ResolvedPatternType.ESCAPED -> true
            ResolvedPatternType.EVALUATED -> (castResult.newData?.parenCount ?: 0) > 0
            else -> false
        }
        if (isEscaped) {
            return DebugStepType.ESCAPE
        }

        if (castResult.cast is ContinuationIota) {
            return DebugStepType.JUMP
        }

        if (newContinuation !is NotDone) {
            return null
        }

        if (newContinuation === continuation.next) {
            // don't emit OUT when finishing a Thoth inner loop
            if (newContinuation.frame is FrameForEach) {
                return null
            }
            return DebugStepType.OUT
        }

        if (continuation.next !== newContinuation.next || continuation.frame.type != newContinuation.frame.type) {
            // don't emit IN when starting a Thoth inner loop
            if (continuation.frame is FrameForEach) {
                return null
            }
            return DebugStepType.IN
        }

        return vm.debugCastEnv.lastDebugStepType
    }

    private fun setIotaOverrides(
        castResult: CastResult,
        continuation: NotDone,
        newContinuation: NotDone,
        stepType: DebugStepType?,
    ) {
        val nextContinuation = newContinuation.next
        val frame = continuation.frame
        val newFrame = newContinuation.frame
        val nextFrame = nextContinuation.frame

        if (stepType == DebugStepType.IN) {
            trySetIotaOverride(newContinuation, castResult)
            if (nextFrame !is FrameEvaluate) {
                trySetIotaOverride(nextContinuation, castResult)
            }
        } else if (
            frame is FrameForEach
            && newFrame is FrameEvaluate
            && nextFrame is FrameForEach
            && frame.code === newFrame.list
            && frame.code === nextFrame.code
        ) {
            // carry over thoth metadata between iterations
            frameInvocationMetadata[nextContinuation] = frameInvocationMetadata[continuation]
        }
    }

    private fun trySetIotaOverride(continuation: SpellContinuation, castResult: CastResult): Boolean {
        return if (continuation !in frameInvocationMetadata && continuation is NotDone) {
            frameInvocationMetadata[continuation] = { castResult.cast to iotaMetadata[castResult.cast] }
            true
        } else false
    }

    private fun iotaToString(iota: Iota, isSource: Boolean = false): String = if (isSource) {
        iota.toHexpatternSource(defaultEnv)
    } else {
        iota.displayWithPatternName(defaultEnv).string
    }

    data class EvaluatorResetData(
        val continuation: SpellContinuation,
        val image: CastingImage,
        val lastResolutionType: ResolvedPatternType,
        val isAtCaughtMishap: Boolean,
    )
}

val SpellContinuation.frame get() = (this as? NotDone)?.frame

val SpellContinuation.next get() = (this as? NotDone)?.next

val ContinuationFrame.name get() = this::class.simpleName ?: "Unknown"

enum class RequestStepType {
    OVER,
    OUT,
}
