package arrow.check

import arrow.check.gen.Gen
import arrow.check.gen.int
import arrow.check.gen.list
import arrow.check.gen.tupledN
import arrow.check.property.Property
import arrow.check.property.PropertyConfig
import arrow.check.property.assert
import arrow.check.property.classify
import arrow.check.property.failWith
import arrow.check.property.failure
import kotlinx.coroutines.delay
import pretty.text

class RunnerTest : PropertySpec({
    "empty property"(Gen.just(Unit), PropertyConfig.default() + PropertyConfig.once()) {
        checkReport(null, Property<Unit>(Gen.just(Unit)) {}.once())
            .let {
                if (it.coverage.unCoverage.isNotEmpty()) failWith("Coverage was not empty")
                if (it.numDiscarded.unDiscardCount != 0) failWith("Tests were discarded")
                if (it.numTests.unTestCount != 1) failWith("Test ran more than once")
                if (it.status !is Result.Success) failWith("Test was not successful")
            }
    }

    "fail property"(Gen.just(Unit), PropertyConfig.default() + PropertyConfig.once()) {
        checkReport(null, Property(Gen.just(Unit)) { failure() }.once())
            .let {
                if (it.coverage.unCoverage.isNotEmpty()) failWith("Coverage was not empty")
                if (it.numDiscarded.unDiscardCount != 0) failWith("Tests were discarded")
                if (it.numTests.unTestCount != 1) failWith("Test ran more than once")
                if (it.status !is Result.Failure) failWith("Test did not fail")
                (it.status as Result.Failure).summary.also {
                    if (it.annotations.isNotEmpty()) failWith("Annotations were not empty")
                    if (it.footnotes.isNotEmpty()) failWith(("Footnotes were not empty"))
                    if (it.numShrinks.unShrinkCount != 0) failWith("Empty property shrank")
                }
            }
    }

    // a few basic property tests to validate they work
    "Int.commutativity"(Gen.tupledN(Gen.int(0..10), Gen.int(0..10))) { (i, j) ->

        classify("I: Not 0", i != 0)
        classify("J: Not 0", j != 0)

        assert(i + j == j + i) { "Not commutative".text() }
    }

    "List.reverse roundtrips"(Gen.int(0..100).list(0..10)) { xs ->

        classify("Empty lists", xs.isEmpty())
        classify("One element lists", xs.size == 1)
        classify("Multi element lists", xs.size > 1)

        xs.roundtrip({ it.reversed() }, { it.reversed() })
    }

    // test interleaved suspension
    "Interleaved delay on a failed test"(Property(Gen.unit()) { _: Unit ->
        // This validates interleaved suspension even on multishot uses during shrinking
        val res = checkReport(null, Property(Gen.int(0..100)) { xs ->
            delay(10)
            assert(xs <= 10)
        }).let { it.status !is Result.Success }

        assert(res) { "Test did not fail".text() }
    }.once())
})
