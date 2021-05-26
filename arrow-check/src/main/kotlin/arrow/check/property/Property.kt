package arrow.check.property

import arrow.typeclasses.Show

// -------------- Property
/**
 * Constructor function to create [Property]'s.
 */
fun <A> property(config: PropertyConfig = PropertyConfig(), prop: suspend Test.(A) -> Unit): Property<A> = Property(config, prop)

/**
 * A [Property] represents a config together with a property test.
 *
 * The [PropertyConfig] holds information about how the property [prop] will be run.
 */
data class Property<A>(val config: PropertyConfig, val prop: suspend Test.(A) -> Unit) {

    /**
     * Change the config of a [Property]
     */
    fun mapConfig(f: (PropertyConfig) -> PropertyConfig): Property<A> =
        copy(config = f(config))

    /**
     * Change the max number of tests that the [Property] will be run with.
     *
     * Should the current [TerminationCriteria] be confidence based this value may be ignored.
     */
    fun withTests(i: Int): Property<A> =
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
    fun withConfidence(c: Confidence): Property<A> =
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
    fun verifiedTermination(): Property<A> =
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
    fun withTerminationCriteria(i: TerminationCriteria): Property<A> =
        mapConfig { PropertyConfig.terminationCriteria.set(it, i) }

    /**
     * Set the max ratio of discarded tests vs total tests. When this ratio is passed the test is aborted and
     *  reported as failed.
     */
    fun withDiscardLimit(i: Double): Property<A> =
        mapConfig { PropertyConfig.maxDiscardRatio.set(it, DiscardRatio(i)) }

    /**
     * Set the maximum depth to which a testcase will shrink.
     *
     * > This is not related to the total shrink runs, only to how many successful shrinks in a row
     *  will be attempted.
     */
    fun withShrinkLimit(i: Int): Property<A> =
        mapConfig { PropertyConfig.shrinkLimit.set(it, ShrinkLimit(i)) }

    /**
     * Set [TestLimit] to 1.
     */
    fun once(): Property<A> = withTests(1)

    companion object
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
fun Test.cover(p: Double, name: String, bool: Boolean): Unit =
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
fun Test.classify(name: String, bool: Boolean): Unit =
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
fun Test.label(name: String): Unit =
    cover(0.0, name, true)

/**
 * Append a top level coverage label to the log.
 *
 * This label will require a percentage of 0 to be covered, so it is effectively always covered.
 *
 * @param SA Optional show instance, default is [Any.toString].
 */
fun <A> Test.collect(a: A, SA: Show<A> = Show.any()): Unit =
    cover(0.0, SA.run { a.show() }, true)

/**
 * Append a coverage label to a specific table rather than have it top level.
 *
 * Performs the same as [cover] however it inserts the label to a separate [table].
 */
fun Test.coverTable(table: String, p: Double, name: String, bool: Boolean): Unit =
    writeLog(JournalEntry.JournalLabel(Label(LabelTable(table), LabelName(name), CoverPercentage(p), bool)))

/**
 *  Append a label to a specific table rather than have it top level.
 *
 * Performs the same as [label] however it inserts the label to a separate [table].
 */
fun Test.tabulate(table: String, name: String): Unit =
    coverTable(table, 0.0, name, true)
