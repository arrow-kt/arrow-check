package arrow.check

import arrow.Kind
import arrow.check.gen.RandSeed
import arrow.check.gen.Rose
import arrow.check.gen.RoseF
import arrow.check.gen.RoseFPartialOf
import arrow.check.gen.fix
import arrow.check.gen.instances.birecursive
import arrow.check.property.CoverCount
import arrow.check.property.Coverage
import arrow.check.property.DiscardCount
import arrow.check.property.EarlyTermination
import arrow.check.property.Failure
import arrow.check.property.JournalEntry
import arrow.check.property.Log
import arrow.check.property.Markup
import arrow.check.property.NoConfidenceTermination
import arrow.check.property.NoEarlyTermination
import arrow.check.property.Property
import arrow.check.property.PropertyConfig
import arrow.check.property.PropertyName
import arrow.check.property.PropertyT
import arrow.check.property.PropertyTestSyntax
import arrow.check.property.ShrinkCount
import arrow.check.property.Size
import arrow.check.property.TestCount
import arrow.check.property.coverage
import arrow.check.property.coverageSuccess
import arrow.check.property.defaultMinTests
import arrow.check.property.failure
import arrow.check.property.fix
import arrow.check.property.instances.monadError
import arrow.check.property.instances.monadTest
import arrow.check.property.property
import arrow.check.property.success
import arrow.core.Either
import arrow.core.Eval
import arrow.core.Id
import arrow.core.None
import arrow.core.Option
import arrow.core.Tuple2
import arrow.core.extensions.id.traverse.traverse
import arrow.core.extensions.list.functorFilter.filterMap
import arrow.core.extensions.sequence.foldable.foldRight
import arrow.core.left
import arrow.core.right
import arrow.core.some
import arrow.core.toT
import arrow.core.value
import arrow.fx.IO
import arrow.fx.IO.Companion.effect
import arrow.fx.extensions.fx
import arrow.fx.extensions.io.functor.void
import arrow.fx.extensions.io.monadDefer.monadDefer
import arrow.fx.fix
import arrow.fx.typeclasses.MonadDefer
import arrow.mtl.OptionTPartialOf
import arrow.mtl.typeclasses.Nested
import arrow.mtl.typeclasses.nest
import arrow.mtl.typeclasses.unnest
import arrow.mtl.value
import arrow.recursion.elgotM
import arrow.recursion.hylo
import arrow.typeclasses.Functor
import arrow.typeclasses.Monad
import pretty.Doc
import pretty.spaced
import pretty.text
import kotlin.random.Random

fun checkGroup(groupName: String, props: List<Tuple2<String, Property>>): IO<Boolean> =
    detectConfig().flatMap { checkGroup(it, groupName, props) }

fun checkGroup(config: Config, groupName: String, props: List<Tuple2<String, Property>>): IO<Boolean> =
    IO.fx {
        !effect { println("━━━ $groupName ━━━") }

        val summary =
            props.fold(IO { Summary.monoid().empty().copy(waiting = PropertyCount(props.size)) }) { acc, (n, prop) ->
                IO.fx {
                    val currSummary = acc.bind()
                    val res = checkReport(config, PropertyName(n).some(), prop).bind()
                    Summary.monoid().run {
                        currSummary + empty().copy(waiting = PropertyCount(-1)) +
                                when (res.status) {
                                    is Result.Failure -> empty().copy(failed = PropertyCount(1))
                                    is Result.Success -> empty().copy(successful = PropertyCount(1))
                                    is Result.GivenUp -> empty().copy(gaveUp = PropertyCount(1))
                                }
                    }
                }
            }.bind()

        summary.failed.unPropertyCount == 0 && summary.gaveUp.unPropertyCount == 0
    }

fun check(
    propertyConfig: PropertyConfig = PropertyConfig(),
    c: suspend PropertyTestSyntax.() -> Unit
): IO<Boolean> =
    check(property(propertyConfig, c))

fun check(
    config: Config,
    propertyConfig: PropertyConfig = PropertyConfig(),
    c: suspend PropertyTestSyntax.() -> Unit
): IO<Boolean> =
    check(config, property(propertyConfig, c))

fun check(prop: Property): IO<Boolean> =
    detectConfig().flatMap { check(it, prop) }

fun check(config: Config, prop: Property): IO<Boolean> = check(config, None, prop)

fun recheck(size: Size, seed: RandSeed, prop: Property): IO<Unit> =
    detectConfig().flatMap { recheck(it, size, seed, prop) }

fun recheck(
    size: Size,
    seed: RandSeed,
    propertyConfig: PropertyConfig = PropertyConfig(),
    c: suspend PropertyTestSyntax.() -> Unit
): IO<Unit> =
    recheck(size, seed, property(propertyConfig, c))

fun recheck(
    config: Config,
    size: Size,
    seed: RandSeed,
    propertyConfig: PropertyConfig = PropertyConfig(),
    c: suspend PropertyTestSyntax.() -> Unit
): IO<Unit> =
    recheck(config, size, seed, property(propertyConfig, c))

fun recheck(config: Config, size: Size, seed: RandSeed, prop: Property): IO<Unit> =
    checkReport(seed, size, config, None, prop).void()

fun checkNamed(
    name: String,
    propertyConfig: PropertyConfig = PropertyConfig(),
    c: suspend PropertyTestSyntax.() -> Unit
): IO<Boolean> =
    checkNamed(name, property(propertyConfig, c))

fun checkNamed(name: String, prop: Property): IO<Boolean> =
    detectConfig().flatMap { check(it, PropertyName(name).some(), prop) }

fun checkNamed(
    config: Config,
    name: String,
    propertyConfig: PropertyConfig = PropertyConfig(),
    c: suspend PropertyTestSyntax.() -> Unit
): IO<Boolean> =
    check(config, PropertyName(name).some(), property(propertyConfig, c))

fun checkNamed(config: Config, name: String, prop: Property): IO<Boolean> =
    check(config, PropertyName(name).some(), prop)

fun check(config: Config, name: Option<PropertyName>, prop: Property): IO<Boolean> =
    checkReport(config, name, prop).map { it.status is Result.Success }

fun checkReport(name: Option<PropertyName>, prop: Property): IO<Report<Result>> =
    detectConfig().flatMap { c ->
        IO { RandSeed(Random.nextLong()) }.flatMap {
            checkReport(it, Size(0), c, name, prop)
        }
    }

fun checkReport(config: Config, name: Option<PropertyName>, prop: Property): IO<Report<Result>> =
    IO { RandSeed(Random.nextLong()) }.flatMap {
        checkReport(it, Size(0), config, name, prop)
    }

internal fun checkReport(
    seed: RandSeed,
    size: Size,
    config: Config,
    name: Option<PropertyName>,
    prop: Property
): IO<Report<Result>> =
    IO.fx {
        val report = !runProperty(IO.monadDefer(), size, seed, prop.config, prop.prop) {
            // TODO Live update will come back once I finish concurrent output
            IO.unit
        }
        !IO { println(report.renderResult(config.useColor, name)) }
        report
    }.fix()

// ---------------- Running a single property
data class State(
    val numTests: TestCount,
    val numDiscards: DiscardCount,
    val size: Size,
    val seed: RandSeed,
    val coverage: Coverage<CoverCount>
)

// TODO also clean this up... split it apart etc
fun <M> runProperty(
    MM: MonadDefer<M>,
    initialSize: Size,
    initialSeed: RandSeed,
    config: PropertyConfig,
    prop: PropertyT<M, Unit>,
    hook: (Report<Progress>) -> Kind<M, Unit>
): Kind<M, Report<Result>> {
    // Catch all errors M throws and report them using failException. This also catches all errors in all shrink branches the same way
    val wrappedProp = PropertyT.monadError(MM).run {
        prop.handleErrorWith {
            PropertyT.monadTest(MM).failException(it)
        }.fix()
    }

    val (confidence, minTests) = when (config.terminationCriteria) {
        is EarlyTermination -> config.terminationCriteria.confidence.some() to config.terminationCriteria.limit
        is NoEarlyTermination -> config.terminationCriteria.confidence.some() to config.terminationCriteria.limit
        is NoConfidenceTermination -> None to config.terminationCriteria.limit
    }

    fun successVerified(testCount: TestCount, coverage: Coverage<CoverCount>): Boolean =
        testCount.unTestCount.rem(100) == 0 && confidence.fold({ false }, { it.success(testCount, coverage) })

    fun failureVerified(testCount: TestCount, coverage: Coverage<CoverCount>): Boolean =
        testCount.unTestCount.rem(100) == 0 && confidence.fold({ false }, { it.failure(testCount, coverage) })

    return State(
        TestCount(0),
        DiscardCount(0),
        initialSize,
        initialSeed,
        Coverage.monoid(CoverCount.semigroup()).empty()
    ).elgotM({ MM.just(it.value()) }, { (numTests, numDiscards, size, seed, currCoverage) ->
        MM.run {
            hook(Report(numTests, numDiscards, currCoverage, Progress.Running)).flatMap {
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
                        MM.just(Id(State(numTests, numDiscards, Size(0), seed, currCoverage)).right())
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
                        MM.just(finalRep.left())
                    }
                    numDiscards.unDiscardCount >= config.maxDiscardRatio.unDiscardRatio * numTests.unTestCount.coerceAtLeast(
                        minTests.unTestLimit
                    ) ->
                        MM.just(Report(numTests, numDiscards, currCoverage, Result.GivenUp).left())
                    else -> seed.split().let { (s1, s2) ->
                        MM.fx.monad {
                            val res = !wrappedProp.unPropertyT.runTestT
                                .value() // EitherT
                                .value().fix() // WriterT
                                .runGen(s1 toT size)
                                .runRose
                                .value() // OptionT

                            res.fold({
                                // discard
                                Id(
                                    State(
                                        numTests,
                                        DiscardCount(numDiscards.unDiscardCount + 1),
                                        Size(size.unSize + 1),
                                        s2,
                                        currCoverage
                                    )
                                ).right()
                            }, { node ->
                                node.res.let { (log, result) ->
                                    result.fold({
                                        // shrink failure
                                        val shrinkRes = !shrinkResult(
                                            MM,
                                            size,
                                            seed,
                                            config.shrinkLimit.unShrinkLimit,
                                            config.shrinkRetries.unShrinkRetries,
                                            node.some().unwrap(MM)
                                        ) { s ->
                                            hook(
                                                Report(
                                                    TestCount(numTests.unTestCount + 1),
                                                    numDiscards,
                                                    currCoverage,
                                                    Progress.Shrinking(s)
                                                )
                                            )
                                        }

                                        Report(
                                            TestCount(numTests.unTestCount + 1),
                                            numDiscards, currCoverage, shrinkRes
                                        ).left()
                                    }, {
                                        // test success
                                        val newCover = Coverage.monoid(CoverCount.semigroup()).run {
                                            log.coverage() + currCoverage
                                        }
                                        Id(
                                            State(
                                                TestCount(numTests.unTestCount + 1),
                                                numDiscards,
                                                Size(size.unSize + 1),
                                                s2,
                                                newCover
                                            )
                                        ).right()
                                    })
                                }
                            })
                        }
                    }
                }
            }
        }
    }, Id.traverse(), MM)
}

// TODO inline classes for params
fun <M> shrinkResult(
    MM: Monad<M>,
    size: Size,
    seed: RandSeed,
    shrinkLimit: Int,
    shrinkRetries: Int,
    node: RoseF<Option<Tuple2<Log, Either<Failure, Unit>>>, Rose<M, Option<Tuple2<Log, Either<Failure, Unit>>>>>,
    hook: (FailureSummary) -> Kind<M, Unit>
): Kind<M, Result> = Rose.birecursive<M, Option<Tuple2<Log, Either<Failure, Unit>>>>(MM).run {
    Rose(MM.just(node)).hylo<Nested<M, RoseFPartialOf<Option<Tuple2<Log, Either<Failure, Unit>>>>>, Rose<M, Option<Tuple2<Log, Either<Failure, Unit>>>>, (ShrinkCount) -> Kind<M, Result>>(
        {
            val curr = it.unnest()
            curr.let {
                { numShrinks: ShrinkCount ->
                    MM.fx.monad {
                        val (res, shrinks) = it.bind().fix()
                        res.fold({
                            Result.GivenUp
                        }, { (log, result) ->
                            result.fold({
                                val summary = FailureSummary(
                                    size, seed, numShrinks, it.unFailure,
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

                                hook(summary).bind()

                                if (numShrinks.unShrinkCount >= shrinkLimit)
                                    Result.Failure(summary)
                                else shrinks.map { it(ShrinkCount(numShrinks.unShrinkCount + 1)) }
                                    .foldRight(Eval.now(MM.just(Result.Failure(summary)))) { v, acc ->
                                        Eval.now(
                                            MM.fx.monad {
                                                val test = !v
                                                if (test is Result.Failure) test
                                                else acc.value().bind()
                                            }
                                        )
                                    }.value().bind()
                            }, { Result.Success })
                        })
                    }
                }
            }
        },
        {
            it.runTreeN(MM, shrinkRetries).nest()
        },
        FF()
    ).invoke(ShrinkCount(0))
}

fun <M, A, L, W> Rose<M, Option<Tuple2<W, Either<L, A>>>>.runTreeN(
    MM: Monad<M>,
    retries: Int
): Kind<M, RoseF<Option<Tuple2<W, Either<L, A>>>, Rose<M, Option<Tuple2<W, Either<L, A>>>>>> =
    MM.fx.monad {
        val r = runRose.bind()
        if (retries > 0 && r.isFailure().not())
            runTreeN(MM, retries - 1).bind()
        else r
    }

fun <M, A> Option<RoseF<A, Rose<OptionTPartialOf<M>, A>>>.unwrap(FF: Functor<M>): RoseF<Option<A>, Rose<M, Option<A>>> =
    fold({
        RoseF(None, emptySequence())
    }, {
        RoseF(
            it.res.some(),
            it.shrunk.map { r -> FF.run { Rose(r.runRose.value().map { opt -> opt.unwrap(FF) }) } })
    })

fun <M, A, L, W> RoseF<Option<Tuple2<W, Either<L, A>>>, M>.isFailure(): Boolean =
    res.fold({ false }, { it.b.isLeft() })
