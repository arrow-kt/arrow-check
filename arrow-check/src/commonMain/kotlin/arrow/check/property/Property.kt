package arrow.check.property

import arrow.check.gen.Gen

// -------------- Property
/**
 * A [Property] represents a config together with a property test.
 *
 * The [PropertyConfig] holds information about how the property [prop] will be run.
 */
public data class Property<A>(val gen: Gen<Any?, A>, val config: PropertyConfig = PropertyConfig(), val prop: suspend Test.(A) -> Unit) {

  /**
   * Change the config of a [Property]
   */
  public fun mapConfig(f: (PropertyConfig) -> PropertyConfig): Property<A> =
    copy(config = f(config))

  /**
   * Change the max number of tests that the [Property] will be run with.
   *
   * Should the current [TerminationCriteria] be confidence based this value may be ignored.
   */
  public fun withTests(i: Int): Property<A> =
    mapConfig {
      it.copy(terminationCriteria = when (val t = it.terminationCriteria) {
        is EarlyTermination -> EarlyTermination(t.confidence, TestLimit(i))
        is NoEarlyTermination -> NoEarlyTermination(t.confidence, TestLimit(i))
        is NoConfidenceTermination -> NoConfidenceTermination(TestLimit(i))
      })
    }

  /**
   * Change the [Confidence] with which a property test determines whether or not label coverage has been
   *  reached or is deemed unreachable.
   */
  public fun withConfidence(c: Confidence): Property<A> =
    mapConfig {
      it.copy(
        terminationCriteria = when (val t = it.terminationCriteria) {
          is EarlyTermination -> EarlyTermination(c, t.limit)
          is NoEarlyTermination -> NoEarlyTermination(c, t.limit)
          is NoConfidenceTermination -> NoEarlyTermination(c, t.limit)
        }
      )
    }

  /**
   * Change the [TerminationCriteria] to [EarlyTermination].
   *
   * Keeps the same [TestLimit] and [Confidence].
   */
  public fun verifiedTermination(): Property<A> =
    mapConfig {
      it.copy(
        terminationCriteria = when (val t = it.terminationCriteria) {
          is EarlyTermination -> t
          is NoEarlyTermination -> EarlyTermination(t.confidence, t.limit)
          is NoConfidenceTermination -> EarlyTermination(Confidence(), t.limit)
        }
      )
    }

  /**
   * Change the [TerminationCriteria]
   */
  public fun withTerminationCriteria(i: TerminationCriteria): Property<A> =
    mapConfig { it.copy(terminationCriteria = i) }

  /**
   * Set the max ratio of discarded tests vs total tests. When this ratio is passed the test is aborted and
   *  reported as failed.
   */
  public fun withDiscardLimit(i: Double): Property<A> =
    mapConfig { it.copy(maxDiscardRatio = DiscardRatio(i)) }

  /**
   * Set the maximum depth to which a testcase will shrink.
   *
   * > This is not related to the total shrink runs, only to how many successful shrinks in a row
   *  will be attempted.
   */
  public fun withShrinkLimit(i: Int): Property<A> =
    mapConfig { it.copy(shrinkLimit = ShrinkLimit(i)) }

  /**
   * Set [TestLimit] to 1.
   */
  public fun once(): Property<A> = withTests(1)

  public companion object
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
public fun Test.cover(p: Double, name: String, bool: Boolean): Unit =
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
public fun Test.classify(name: String, bool: Boolean): Unit =
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
public fun Test.label(name: String): Unit =
  cover(0.0, name, true)

/**
 * Append a top level coverage label to the log.
 *
 * This label will require a percentage of 0 to be covered, so it is effectively always covered.
 *
 * @param SA Optional show instance, default is [Any.toString].
 */
public fun <A> Test.collect(a: A, SA: (A) -> String = { it.toString() }): Unit =
  cover(0.0, SA(a), true)

/**
 * Append a coverage label to a specific table rather than have it top level.
 *
 * Performs the same as [cover] however it inserts the label to a separate [table].
 */
public fun Test.coverTable(table: String, p: Double, name: String, bool: Boolean): Unit =
  writeLog(JournalEntry.JournalLabel(Label(LabelTable(table), LabelName(name), CoverPercentage(p), bool)))

/**
 *  Append a label to a specific table rather than have it top level.
 *
 * Performs the same as [label] however it inserts the label to a separate [table].
 */
public fun Test.tabulate(table: String, name: String): Unit =
  coverTable(table, 0.0, name, true)
