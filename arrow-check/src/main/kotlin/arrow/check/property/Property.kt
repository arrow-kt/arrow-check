package arrow.check.property

import arrow.check.gen.Gen
import arrow.check.gen.discard
import arrow.check.pretty.showPretty
import arrow.typeclasses.Show
import pretty.Doc

// -------------- Property
/**
 * Constructor function to create [Property]'s.
 */
fun property(config: PropertyConfig = PropertyConfig(), prop: suspend PropertyTest.() -> Unit): Property = Property(config, prop)

/**
 * A [Property] represents a config together with a property test.
 *
 * The [PropertyConfig] holds information about how the property [prop] will be run.
 */
data class Property(val config: PropertyConfig, val prop: suspend PropertyTest.() -> Unit) {

    /**
     * Change the config of a [Property]
     */
    fun mapConfig(f: (PropertyConfig) -> PropertyConfig): Property =
        copy(config = f(config))

    /**
     * Change the max number of tests that the [Property] will be run with.
     *
     * Should the current [TerminationCriteria] be confidence based this value may be ignored.
     */
    fun withTests(i: Int): Property =
        mapConfig {
            PropertyConfig.terminationCriteria.modify(it) {
                val tl = TestLimit(i)
                when (it) {
                    is EarlyTermination -> EarlyTermination(it.confidence, tl)
                    is NoEarlyTermination -> NoEarlyTermination(it.confidence, tl)
                    is NoConfidenceTermination -> NoConfidenceTermination(tl)
                }
            }
        }

    /**
     * Change the [Confidence] with which a property test determines whether or not label coverage has been
     *  reached or is deemed unreachable.
     */
    fun withConfidence(c: Confidence): Property =
        mapConfig {
            PropertyConfig.terminationCriteria.modify(it) {
                when (it) {
                    is EarlyTermination -> EarlyTermination(c, it.limit)
                    is NoEarlyTermination -> NoEarlyTermination(c, it.limit)
                    is NoConfidenceTermination -> NoEarlyTermination(c, it.limit)
                }
            }
        }

    /**
     * Change the [TerminationCriteria] to [EarlyTermination].
     *
     * Keeps the same [TestLimit] and [Confidence].
     */
    fun verifiedTermination(): Property =
        mapConfig {
            PropertyConfig.terminationCriteria.modify(it) {
                when (it) {
                    is EarlyTermination -> it
                    is NoEarlyTermination -> EarlyTermination(it.confidence, it.limit)
                    is NoConfidenceTermination -> EarlyTermination(Confidence(), it.limit)
                }
            }
        }

    /**
     * Change the [TerminationCriteria]
     */
    fun withTerminationCriteria(i: TerminationCriteria): Property =
        mapConfig { PropertyConfig.terminationCriteria.set(it, i) }

    /**
     * Set the max ratio of discarded tests vs total tests. When this ratio is passed the test is aborted and
     *  reported as failed.
     */
    fun withDiscardLimit(i: Double): Property =
        mapConfig { PropertyConfig.maxDiscardRatio.set(it, DiscardRatio(i)) }

    /**
     * Set the maximum depth to which a testcase will shrink.
     *
     * > This is not related to the total shrink runs, only to how many successful shrinks in a row
     *  will be attempted.
     */
    fun withShrinkLimit(i: Int): Property =
        mapConfig { PropertyConfig.shrinkLimit.set(it, ShrinkLimit(i)) }

    /**
     * Set [TestLimit] to 1.
     */
    fun once(): Property = withTests(1)

    companion object
}

/**
 * A [PropertyTest] extends [Test] with [PropertyTest.forAllWith] which enables running a generator.
 */
interface PropertyTest : Test {

    /**
     * Run a generator and return its result.
     *
     * @param showA A custom show method
     * @param env Environment to pass to the generator
     */
    suspend fun <R, A> forAllWith(showA: (A) -> Doc<Markup>, env: R, gen: Gen<R, A>): A

    /**
     * Run a generator and return its result.
     *
     * @param showA A custom show method
     */
    suspend fun <A> forAllWith(showA: (A) -> Doc<Markup>, gen: Gen<Any?, A>): A =
        forAllWith(showA, Unit, gen)

    /**
     * Run a generator and return its result.
     *
     * @param env Environment to pass to the generator
     * @param SA Optional show instance, default uses [Any.toString]
     */
    suspend fun <R, A> forAll(env: R, gen: Gen<R, A>, SA: Show<A> = Show.any()): A =
        forAllWith({ a -> a.showPretty(SA) }, env, gen)

    /**
     * Run a generator and return its result.
     *
     * @param SA Optional show instance, default uses [Any.toString]
     */
    suspend fun <A> forAll(gen: Gen<Any?, A>, SA: Show<A> = Show.any()): A =
        forAll(Unit, gen, SA)

    /**
     * Discard a test run.
     */
    suspend fun discard(): Nothing = forAll(Gen.discard())
}

/**
 * Append a top level coverage label to the log. This will later be used to check if the overall
 *  number of labels reaches the required percentage.
 *
 * @param p Percentage to reach. e.g. 50 => 50%, 0.1 => 0.1% etc
 * @param name Identifier of this label, used as a key to sum the labels later.
 * @param bool Condition whether or not this label is counted or not.
 *
 * @see cover To enforce a coverage percentage
 * @see classify To have control over whether or not this label should be counted.
 * @see coverTable To append a covered label to a sub-table.
 */
fun PropertyTest.cover(p: Double, name: String, bool: Boolean): Unit =
    writeLog(JournalEntry.JournalLabel(Label(null, LabelName(name), CoverPercentage(p), bool)))

/**
 * Append a top level coverage label to the log.
 *
 * This label will require a percentage of 0 to be covered, so it is effectively always covered.
 *
 * @param name Identifier of this label, used as a key to sum the labels later.
 * @param bool Condition whether or not this label is counted or not.
 *
 * @see cover To enforce a coverage percentage
 * @see classify To have control over whether or not this label should be counted.
 * @see tabulate To append a label to a sub-table.
 */
fun PropertyTest.classify(name: String, bool: Boolean): Unit =
    cover(0.0, name, bool)

/**
 * Append a top level coverage label to the log.
 *
 * This label will require a percentage of 0 to be covered, so it is effectively always covered.
 *
 * @param name Identifier of this label, used as a key to sum the labels later.
 *
 * @see cover To enforce a coverage percentage
 * @see classify To have control over whether or not this label should be counted.
 * @see tabulate To append a label to a sub-table.
 */
fun PropertyTest.label(name: String): Unit =
    cover(0.0, name, true)

/**
 * Append a top level coverage label to the log.
 *
 * This label will require a percentage of 0 to be covered, so it is effectively always covered.
 *
 * @param SA Optional show instance, default is [Any.toString].
 */
fun <A> PropertyTest.collect(a: A, SA: Show<A> = Show.any()): Unit =
    cover(0.0, SA.run { a.show() }, true)

/**
 * Append a coverage label to a specific table rather than have it top level.
 *
 * Performs the same as [cover] however it inserts the label to a separate [table].
 */
fun PropertyTest.coverTable(table: String, p: Double, name: String, bool: Boolean): Unit =
    writeLog(JournalEntry.JournalLabel(Label(LabelTable(table), LabelName(name), CoverPercentage(p), bool)))

/**
 *  Append a label to a specific table rather than have it top level.
 *
 * Performs the same as [label] however it inserts the label to a separate [table].
 */
fun PropertyTest.tabulate(table: String, name: String): Unit =
    coverTable(table, 0.0, name, true)
