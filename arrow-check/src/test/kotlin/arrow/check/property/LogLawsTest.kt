package arrow.check.property

import arrow.core.k
import arrow.test.UnitSpec
import arrow.test.generators.option
import arrow.test.laws.MonoidLaws
import arrow.typeclasses.Eq
import io.kotlintest.properties.Gen
import pretty.doc

fun <A> Label.Companion.gen(gen: Gen<A>): Gen<Label<A>> =
    Gen.bind(
        Gen.option(Gen.string().map { LabelTable(it) }),
        Gen.string().map { LabelName(it) },
        Gen.double().map { CoverPercentage(it) },
        gen
    ) { t, l, p, b -> Label(t, l, p, b) }

class LogLawsTest : UnitSpec() {
    init {
        val journalEntryGen = Gen.oneOf(
            Gen.string().map { JournalEntry.Input { it.doc() } },
            Gen.string().map { JournalEntry.Annotate { it.doc() } },
            Gen.string().map { JournalEntry.Footnote { it.doc() } },
            Label.gen(Gen.bool()).map { JournalEntry.JournalLabel(it) }
        )

        testLaws(
            MonoidLaws.laws(
                Log.monoid(),
                Gen.list(journalEntryGen).map { Log(it.k()) },
                Eq.any() // sufficient here
            )
        )
    }
}
