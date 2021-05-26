package arrow.check.property

import arrow.check.FailureAnnotation
import arrow.check.FailureSummary
import arrow.check.Progress
import arrow.check.Report
import arrow.check.gen.Gen
import arrow.check.gen.RandSeed
import arrow.check.gen.Rose
import arrow.check.gen.flatMap
import arrow.check.testCount
import arrow.core.Tuple3
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.collect
import pretty.Doc
import pretty.hardLine
import pretty.plus
import pretty.spaced
import pretty.text
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume

internal data class State(
  val numTests: TestCount,
  val numDiscards: DiscardCount,
  val size: Size,
  val seed: RandSeed,
  val coverage: Coverage<CoverCount>
)

// ---------------- Running a single property
internal class PropertyTestImpl : Test {

  internal var logs: MutableList<JournalEntry> = mutableListOf()

  fun getAndClearLogs(): List<JournalEntry> = logs.also { logs = mutableListOf() }

  override fun writeLog(log: JournalEntry) {
    logs.add(log)
  }

  override fun failWith(msg: Doc<Markup>): Nothing {
    throw ShortCircuit.Failure(Failure(msg))
  }

  sealed class ShortCircuit : Throwable() {
    class Failure(val fail: arrow.check.property.Failure) : ShortCircuit()
  }
}

internal sealed class TestResult<out A> {
  abstract val log: Log

  data class Success<A>(val res: A, override val log: Log) : TestResult<A>()
  data class Failed(val failure: Failure, override val log: Log) : TestResult<Nothing>()
}

internal fun <A> TestResult<A>.prependLog(oldLog: Log): TestResult<A> = when (this) {
  is TestResult.Success -> copy(log = Log.monoid().run { oldLog + log })
  is TestResult.Failed -> copy(log = Log.monoid().run { oldLog + log })
}

internal suspend inline fun <A> execPropertyTest(
  gen: Gen<Any?, A>,
  crossinline prop: suspend Test.(A) -> Unit,
  seed: RandSeed,
  size: Size
): Rose<TestResult<Unit>>? {
  val impl = PropertyTestImpl()
  val gen = gen.flatMap { a ->
    Gen {
      try {
        impl.run { prop(a) }
        Rose(TestResult.Success(Unit, Log(impl.getAndClearLogs())))
      } catch (err: PropertyTestImpl.ShortCircuit.Failure) {
        Rose(TestResult.Failed(err.fail, Log(impl.getAndClearLogs())))
      } catch (err: Throwable) {
        val doc = "━━━ Failed: (Exception) ━━━".text() + hardLine() + err.toString().text()
        Rose(TestResult.Failed(Failure(doc), Log(impl.getAndClearLogs())))
      }
    }
  }
  return gen.runGen(Tuple3(seed, size, Unit))
}

// TODO also clean this up... split it apart etc
internal suspend fun <A> runProperty(
  initialSize: Size,
  initialSeed: RandSeed,
  config: PropertyConfig,
  gen: Gen<Any?, A>,
  prop: suspend Test.(A) -> Unit,
  hook: suspend (Report<Progress>) -> Unit
): Report<arrow.check.Result> {
  val (confidence, minTests) = when (config.terminationCriteria) {
    is EarlyTermination -> config.terminationCriteria.confidence to config.terminationCriteria.limit
    is NoEarlyTermination -> config.terminationCriteria.confidence to config.terminationCriteria.limit
    is NoConfidenceTermination -> null to config.terminationCriteria.limit
  }

  fun successVerified(testCount: TestCount, coverage: Coverage<CoverCount>): Boolean =
    testCount.unTestCount.rem(100) == 0 && confidence?.success(testCount, coverage) ?: false

  fun failureVerified(testCount: TestCount, coverage: Coverage<CoverCount>): Boolean =
    testCount.unTestCount.rem(100) == 0 && confidence?.failure(testCount, coverage) ?: false

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
        fun failureRep(msg: Doc<Markup>): Report<arrow.check.Result> = Report(
          numTests, numDiscards, currCoverage,
          arrow.check.Result.Failure(
            FailureSummary(
              size, seed, ShrinkCount(0), msg, emptyList(), emptyList()
            )
          )
        )

        val successRep = Report(numTests, numDiscards, currCoverage, arrow.check.Result.Success)
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
      ) -> return@runProperty Report(numTests, numDiscards, currCoverage, arrow.check.Result.GivenUp)
      else -> {
        seed.split().let { (s1, s2) ->
          val res = execPropertyTest(gen, prop, s1, size)
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
}

internal suspend fun shrinkResult(
  size: Size,
  seed: RandSeed,
  shrinkLimit: ShrinkLimit,
  node: Rose<TestResult<Unit>>,
  hook: suspend (FailureSummary) -> Unit
): arrow.check.Result {
  var shrinkCount = 0
  var current: Rose<TestResult<Unit>> = node

  fun makeSummary(log: Log, fail: Failure): FailureSummary {
    return FailureSummary(
      size, seed, ShrinkCount(shrinkCount), fail.unFailure,
      annotations = log.unLog.mapNotNull { entry ->
        when (entry) {
          is JournalEntry.Annotate -> FailureAnnotation.Annotation(entry.text)
          is JournalEntry.Input -> FailureAnnotation.Input(entry.text)
          else -> null
        }
      },
      footnotes = log.unLog.mapNotNull {
        if (it is JournalEntry.Footnote) it.text
        else null
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
  return arrow.check.Result.Failure(best)
}

internal object AbortFlowException : Throwable()

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

  inline fun complete(a: A, f: (A, A) -> A): Unit =
    when (val curr = state.value) {
      EMPTY -> state.compareAndSet(EMPTY, a).let { }
      is Waiting<*> -> {
        state.compareAndSet(curr, a)
        (curr.cont as Continuation<A>).resume(a)
      }
      else -> state.compareAndSet(curr, f(a, curr as A)).let {}
    }

  object EMPTY
  class Waiting<A>(val cont: Continuation<A>)
}

private val coroutineImplClass by lazy { Class.forName("kotlin.coroutines.jvm.internal.BaseContinuationImpl") }

private val completionField by lazy { coroutineImplClass.getDeclaredField("completion").apply { isAccessible = true } }

private val coroutineImplClass2 by lazy { Class.forName("kotlin.coroutines.jvm.internal.ContinuationImpl") }

internal val intercepted by lazy { coroutineImplClass2.getDeclaredField("intercepted").apply { isAccessible = true } }

private var <T> Continuation<T>.completion: Continuation<*>?
  get() = completionField.get(this) as Continuation<*>
  set(value) = completionField.set(this@completion, value)

internal var <T> Continuation<T>.stateStack: List<Map<String, *>>
  get() {
    if (!coroutineImplClass.isInstance(this)) return emptyList()
    val resultForThis = (this.javaClass.declaredFields)
      .associate { it.isAccessible = true; it.name to it.get(this@stateStack) }
      .let(::listOf)
    val resultForCompletion = completion?.stateStack
    return resultForCompletion?.let { resultForThis + it } ?: resultForThis
  }
  set(value) {
    if (!coroutineImplClass.isInstance(this)) return
    val mapForThis = value.first()
    (this.javaClass.declaredFields).forEach {
      if (it.name in mapForThis) {
        it.isAccessible = true
        val fieldValue = mapForThis[it.name]
        it.set(this@stateStack, fieldValue)
      }
    }
    completion?.stateStack = value.subList(1, value.size)
  }
