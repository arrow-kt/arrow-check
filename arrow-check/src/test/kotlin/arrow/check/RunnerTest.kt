package arrow.check

import arrow.check.gen.Gen
import arrow.check.gen.int
import arrow.check.gen.list
import arrow.check.gen.toFunction
import arrow.check.property.*
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

    "fn" {
        // val (f) = forAll(Gen.int(0..100).toFunction(ListToFunction(Unit.toFunction())))
        val (f) = forAll(Gen.int(0..100).toFunction(Long.toFunction()))

        println("Run ${f(1)} | ${f(0)}")
        assert(f(1) < 4)
    }

    // test interleaved suspension
})
