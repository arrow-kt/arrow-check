package arrow.check

import arrow.check.gen.Gen
import arrow.check.gen.int
import arrow.check.gen.list
import arrow.check.property.PropertyConfig
import arrow.check.property.assert
import arrow.check.property.classify
import arrow.check.property.failWith
import arrow.check.property.failure
import arrow.check.property.property
import kotlinx.coroutines.delay
import pretty.text

class RunnerTest : PropertySpec({
    "empty property"(PropertyConfig.default() + PropertyConfig.once()) {
        checkReport(null, property {}.once())
            .let {
                if (it.coverage.unCoverage.isNotEmpty()) failWith("Coverage was not empty")
                if (it.numDiscarded.unDiscardCount != 0) failWith("Tests were discarded")
                if (it.numTests.unTestCount != 1) failWith("Test ran more than once")
                if (it.status !is Result.Success) failWith("Test was not successful")
            }
    }

    "fail property"(PropertyConfig.default() + PropertyConfig.once()) {
        checkReport(null, property { failure() }.once())
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
    "Int.commutativity" {
        val i = forAll(Gen.int(0..10))
        val j = forAll(Gen.int(0..10))

        classify("I: Not 0", i != 0)
        classify("J: Not 0", j != 0)

        assert(i + j == j + i) { "Not commutative".text() }
    }

    "List.reverse roundtrips" {
        val xs = forAll(Gen.int(0..100).list(0..10))

        classify("Empty lists", xs.isEmpty())
        classify("One element lists", xs.size == 1)
        classify("Multi element lists", xs.size > 1)

        xs.roundtrip({ it.reversed() }, { it.reversed() })
    }

    // test interleaved suspension
    "Interleaved delay on a failed test"(property {
        // This validates interleaved suspension even on multishot uses during shrinking
        val res = check {
            val xs = forAll(Gen.int(0..100))
            delay(10)
            assert(xs <= 10)
        }.not()

        assert(res) { "Test did not fail".text() }
    }.once())
})
