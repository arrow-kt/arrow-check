package arrow.check.property

import arrow.core.k
import arrow.test.UnitSpec
import arrow.test.generators.option
import arrow.test.laws.MonoidLaws
import arrow.typeclasses.Eq
import io.kotlintest.properties.Gen
import pretty.doc

class LogLawsTest : UnitSpec() {
    init {
        val journalEntryGen = Gen.oneOf(
            Gen.string().map { JournalEntry.Input { it.doc() } },
            Gen.string().map { JournalEntry.Annotate { it.doc() } },
            Gen.string().map { JournalEntry.Footnote { it.doc() } },
            Gen.bind(
                Gen.option(Gen.string().map { LabelTable(it) }),
                Gen.string().map { LabelName(it) },
                Gen.double().map { CoverPercentage(it) },
                Gen.bool()
            ) { t, l, p, b -> Label(t, l, p, b) }.map { JournalEntry.JournalLabel(it) }
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
