package arrow.check

import arrow.check.gen.*
import arrow.check.property.*
import arrow.core.*
import arrow.core.extensions.list.monadFilter.filterMap
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.collect
import pretty.*
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.random.Random

suspend fun checkGroup(groupName: String, props: List<Tuple2<String, Property>>): Boolean =
    checkGroup(detectConfig(), groupName, props)

suspend fun checkGroup(config: Config, groupName: String, props: List<Tuple2<String, Property>>): Boolean {
    println("━━━ $groupName ━━━")

    val summary =
        props.fold(Summary.monoid().empty().copy(waiting = PropertyCount(props.size))) { acc, (n, prop) ->
            val currSummary = acc
            val res = checkReport(config, PropertyName(n), prop)
            Summary.monoid().run {
                currSummary +
                        empty().copy(waiting = PropertyCount(-1)) +
                        when (res.status) {
                            is Result.Failure -> empty().copy(failed = PropertyCount(1))
                            is Result.Success -> empty().copy(successful = PropertyCount(1))
                            is Result.GivenUp -> empty().copy(gaveUp = PropertyCount(1))
                        }
            }
        }

    return summary.failed.unPropertyCount == 0 && summary.gaveUp.unPropertyCount == 0
}

suspend fun check(
    propertyConfig: PropertyConfig = PropertyConfig(),
    c: suspend PropertyTest.() -> Unit
): Boolean =
    check(property(propertyConfig, c))

suspend fun check(
    config: Config,
    propertyConfig: PropertyConfig = PropertyConfig(),
    c: suspend PropertyTest.() -> Unit
): Boolean =
    check(config, property(propertyConfig, c))

suspend fun check(prop: Property): Boolean =
    check(detectConfig(), prop)

suspend fun check(config: Config, prop: Property): Boolean = check(config, null, prop)

suspend fun recheck(size: Size, seed: RandSeed, prop: Property): Unit =
    recheck(detectConfig(), size, seed, prop)

suspend fun recheck(
    size: Size,
    seed: RandSeed,
    propertyConfig: PropertyConfig = PropertyConfig(),
    c: suspend PropertyTest.() -> Unit
): Unit = recheck(size, seed, property(propertyConfig, c))

suspend fun recheck(
    config: Config,
    size: Size,
    seed: RandSeed,
    propertyConfig: PropertyConfig = PropertyConfig(),
    c: suspend PropertyTest.() -> Unit
): Unit = recheck(config, size, seed, property(propertyConfig, c))

suspend fun recheck(config: Config, size: Size, seed: RandSeed, prop: Property): Unit {
    checkReport(seed, size, config, null, prop)
}

suspend fun checkNamed(
    name: String,
    propertyConfig: PropertyConfig = PropertyConfig(),
    c: suspend PropertyTest.() -> Unit
): Boolean = checkNamed(name, property(propertyConfig, c))

suspend fun checkNamed(name: String, prop: Property): Boolean =
    check(detectConfig(), PropertyName(name), prop)

suspend fun checkNamed(
    config: Config,
    name: String,
    propertyConfig: PropertyConfig = PropertyConfig(),
    c: suspend PropertyTest.() -> Unit
): Boolean = check(config, PropertyName(name), property(propertyConfig, c))

suspend fun checkNamed(config: Config, name: String, prop: Property): Boolean =
    check(config, PropertyName(name), prop)

suspend fun check(config: Config, name: PropertyName?, prop: Property): Boolean =
    checkReport(config, name, prop).status is Result.Success

suspend fun checkReport(name: PropertyName?, prop: Property): Report<Result> =
    checkReport(RandSeed(Random.nextLong()), Size(0), detectConfig(), name, prop)

suspend fun checkReport(config: Config, name: PropertyName?, prop: Property): Report<Result> =
    checkReport(RandSeed(Random.nextLong()), Size(0), config, name, prop)

internal suspend fun checkReport(
    seed: RandSeed,
    size: Size,
    config: Config,
    name: PropertyName?,
    prop: Property
): Report<Result> {
    val report = runProperty(size, seed, prop.config, prop.prop) {
        // TODO Live update will come back once I finish concurrent output
    }
    println(report.renderResult(config.useColor, name))
    return report
}

// ---------------- Running a single property
internal class PropertyTestImpl : PropertyTest {

    var promise: Promise<Gen<Any?, TestResult<*>>> = Promise()

    suspend fun waitFor(): Gen<Any?, TestResult<*>> = promise.await()
        .also { promise = Promise() }

    fun complete(gen: Gen<Any?, TestResult<*>>): Unit = promise.complete(gen).let { }

    override suspend fun <R, A> forAllWith(showA: (A) -> Doc<Markup>, env: R, gen: Gen<R, A>): A =
        suspendCoroutineUninterceptedOrReturn { c ->
            val labelHere = c.stateStack // save the whole coroutine stack labels
            gen.runEnv(env).flatMap { x: A ->
                c.stateStack = labelHere
                // BOOOOO
                val interceptedCont = intercepted.get(c)
                if (interceptedCont !== null && interceptedCont.toString() == "This continuation is already complete")
                    intercepted.set(c, null) // Maybe call intercept here?
                c.resume(x)
                Gen<Any?, Gen<Any?, TestResult<*>>> { Rose(waitFor()) }
                    .flatMap(::identity)
                    .map {
                        val log = JournalEntry.Input { showA(x) }
                        when (it) {
                            is TestResult.Success<*> -> it.copy(log = Log.monoid().run { Log(listOf(log)) + it.log })
                            is TestResult.Failed -> it.copy(log = Log.monoid().run { Log(listOf(log)) + it.log })
                        }
                    }
            }.also(::complete)
            COROUTINE_SUSPENDED
        }

    override suspend fun discard(): Nothing = forAll(Gen.discard())

    override fun writeLog(log: JournalEntry) {
        complete(
            Gen<Any?, Gen<Any?, TestResult<*>>> { Rose(waitFor()) }
                .flatMap {
                    it.map {
                        when (it) {
                            is TestResult.Success<*> -> it.copy(log = Log.monoid().run { it.log + Log(listOf(log)) })
                            is TestResult.Failed -> TODO("Should not be possible")
                        }
                    }
                }
        )
    }

    override fun failWith(msg: Doc<Markup>): Nothing {
        throw ShortCircuit.Failure(Failure(msg))
    }

    sealed class ShortCircuit : Throwable() {
        class Failure(val fail: arrow.check.property.Failure) : ShortCircuit()
    }

}

sealed class TestResult<out A> {
    abstract val log: Log

    data class Success<A>(val res: A, override val log: Log) : TestResult<A>()
    data class Failed(val failure: Failure, override val log: Log) : TestResult<Nothing>()
}

data class State(
  val numTests: TestCount,
  val numDiscards: DiscardCount,
  val size: Size,
  val seed: RandSeed,
  val coverage: Coverage<CoverCount>
)

suspend fun execPropertyTest(
    prop: suspend PropertyTest.() -> Unit,
    seed: RandSeed,
    size: Size
): Rose<TestResult<Unit>>? {
    val impl = PropertyTestImpl()
    prop.startCoroutineUninterceptedOrReturn(impl, Continuation(coroutineContext) {
        if (it.isSuccess) impl.complete(Gen.just(TestResult.Success(Unit, Log.monoid().empty())))
        else {
            when (val exception = it.exceptionOrNull()!!) {
                is PropertyTestImpl.ShortCircuit.Failure -> {
                    impl.complete(Gen.just(TestResult.Failed(exception.fail, Log.monoid().empty())))
                }
                else -> {
                    // Deduplicate...
                    val doc =
                        ("━━━ Failed: (Exception) ━━━".text() + hardLine() + it.exceptionOrNull()!!.toString().doc())
                    impl.complete(Gen.just(TestResult.Failed(Failure(doc), Log.monoid().empty())))
                }
            }
        }
    }).let {
        if (it !== COROUTINE_SUSPENDED) impl.complete(Gen.just(TestResult.Success(Unit, Log.monoid().empty())))
    }
    return impl.waitFor().runGen(Tuple3(seed, size, Unit)) as Rose<TestResult<Unit>>?
}

// TODO also clean this up... split it apart etc
suspend fun runProperty(
    initialSize: Size,
    initialSeed: RandSeed,
    config: PropertyConfig,
    prop: suspend PropertyTest.() -> Unit,
    hook: suspend (Report<Progress>) -> Unit
): Report<Result> {
    val (confidence, minTests) = when (config.terminationCriteria) {
        is EarlyTermination -> config.terminationCriteria.confidence.some() to config.terminationCriteria.limit
        is NoEarlyTermination -> config.terminationCriteria.confidence.some() to config.terminationCriteria.limit
        is NoConfidenceTermination -> None to config.terminationCriteria.limit
    }

    fun successVerified(testCount: TestCount, coverage: Coverage<CoverCount>): Boolean =
        testCount.unTestCount.rem(100) == 0 && confidence.fold({ false }, { it.success(testCount, coverage) })

    fun failureVerified(testCount: TestCount, coverage: Coverage<CoverCount>): Boolean =
        testCount.unTestCount.rem(100) == 0 && confidence.fold({ false }, { it.failure(testCount, coverage) })

    var currState = State(
        TestCount(0),
        DiscardCount(0),
        initialSize,
        initialSeed,
        Coverage.monoid(CoverCount.semigroup()).empty()
    )

    while (true) {
        val (numTests, numDiscards, size, seed, currCoverage) = currState
        hook(Report(numTests, numDiscards, currCoverage, Progress.Running))

        val coverageReached = successVerified(numTests, currCoverage)
        val coverageUnreachable = failureVerified(numTests, currCoverage)

        val enoughTestsRun = when (config.terminationCriteria) {
            is EarlyTermination ->
                numTests.unTestCount >= defaultMinTests.unTestLimit &&
                        (coverageReached || coverageUnreachable)
            is NoEarlyTermination ->
                numTests.unTestCount >= minTests.unTestLimit
            is NoConfidenceTermination ->
                numTests.unTestCount >= minTests.unTestLimit
        }

        when {
            size.unSize > 99 ->
                currState = State(numTests, numDiscards, Size(0), seed, currCoverage)
            enoughTestsRun -> {
                fun failureRep(msg: Doc<Markup>): Report<Result> = Report(
                    numTests, numDiscards, currCoverage,
                    Result.Failure(
                        FailureSummary(
                            size, seed, ShrinkCount(0), msg, emptyList(), emptyList()
                        )
                    )
                )

                val successRep = Report(numTests, numDiscards, currCoverage, Result.Success)
                val labelsCovered = currCoverage.coverageSuccess(numTests)
                val confidenceReport =
                    if (coverageReached && labelsCovered) successRep
                    else failureRep("Test coverage cannot be reached after".text() spaced numTests.testCount())

                val finalRep = when (config.terminationCriteria) {
                    is EarlyTermination -> confidenceReport
                    is NoEarlyTermination -> confidenceReport
                    is NoConfidenceTermination ->
                        if (labelsCovered) successRep
                        else failureRep("Labels not sufficiently covered after".text() spaced numTests.testCount())
                }
                return@runProperty finalRep
            }
            numDiscards.unDiscardCount >= config.maxDiscardRatio.unDiscardRatio * numTests.unTestCount.coerceAtLeast(
                minTests.unTestLimit
            ) -> return@runProperty Report(numTests, numDiscards, currCoverage, Result.GivenUp)
            else -> {
                seed.split().let { (s1, s2) ->
                    val res = execPropertyTest(prop, s1, size)
                    when (res?.res) {
                        null ->
                            currState = State(
                                numTests,
                                DiscardCount(numDiscards.unDiscardCount + 1),
                                Size(size.unSize + 1),
                                s2,
                                currCoverage
                            )
                        is TestResult.Success -> {
                            val newCover = Coverage.monoid(CoverCount.semigroup()).run {
                                res.res.log.coverage() + currCoverage
                            }
                            currState = State(
                                TestCount(numTests.unTestCount + 1),
                                numDiscards,
                                Size(size.unSize + 1),
                                s2,
                                newCover
                            )
                        }
                        is TestResult.Failed -> {
                            val shrinkRes = shrinkResult(
                                size,
                                seed,
                                config.shrinkLimit,
                                res
                            ) {
                                hook(
                                    Report(
                                        TestCount(numTests.unTestCount + 1),
                                        numDiscards,
                                        currCoverage,
                                        Progress.Shrinking(it)
                                    )
                                )
                            }

                            return Report(
                                TestCount(numTests.unTestCount + 1),
                                numDiscards, currCoverage, shrinkRes
                            )
                        }
                    }
                }
            }
        }
    }

    return TODO()
}

suspend fun shrinkResult(
    size: Size,
    seed: RandSeed,
    shrinkLimit: ShrinkLimit,
    node: Rose<TestResult<Unit>>,
    hook: suspend (FailureSummary) -> Unit
): Result {
    var shrinkCount = 0
    var current: Rose<TestResult<Unit>> = node

    fun makeSummary(log: Log, fail: Failure): FailureSummary {
        return FailureSummary(
            size, seed, ShrinkCount(shrinkCount), fail.unFailure,
            annotations = log.unLog.filterMap { entry ->
                when (entry) {
                    is JournalEntry.Annotate -> FailureAnnotation.Annotation(entry.text).some()
                    is JournalEntry.Input -> FailureAnnotation.Input(entry.text).some()
                    else -> None
                }
            },
            footnotes = log.unLog.filterMap { entry ->
                if (entry is JournalEntry.Footnote) entry.text.some()
                else None
            }
        )
    }

    val res = current.res as TestResult.Failed
    var best = makeSummary(res.log, res.failure)

    while (true) {
        if (shrinkCount >= shrinkLimit.unShrinkLimit) break
        try {
            current.shrinks.collect {
                val curr = it
                if (curr == null) Unit
                else {
                    when (val result = curr.res) {
                        is TestResult.Success -> Unit
                        is TestResult.Failed -> {
                            best = makeSummary(result.log, result.failure)
                            current = curr
                            shrinkCount++
                            hook(best)
                            throw AbortFlowException
                        }
                    }
                }
            }
            break
        } catch (exc: AbortFlowException) {
            // Nothing to do we just finished with this flow
        }
    }
    return Result.Failure(best)
}

object AbortFlowException : Throwable()

// Not really a fully fledged promise, but enough for this use case
internal class Promise<A> {

    val state = atomic<Any?>(EMPTY)

    suspend fun await(): A =
        when (val curr = state.value) {
            EMPTY -> suspendCoroutineUninterceptedOrReturn { c ->
                state.compareAndSet(EMPTY, Waiting(c))
                COROUTINE_SUSPENDED
            }
            else -> curr as A
        }

    fun complete(a: A): Unit =
        when (val curr = state.value) {
            EMPTY -> state.compareAndSet(EMPTY, a).let { }
            is Waiting<*> -> {
                state.compareAndSet(curr, a)
                (curr.cont as Continuation<A>).resume(a)
            }
            else -> Unit
        }

    object EMPTY
    class Waiting<A>(val cont: Continuation<A>)
}