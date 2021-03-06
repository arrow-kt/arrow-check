package arrow.check

import arrow.check.gen.RandSeed
import arrow.check.property.CoverCount
import arrow.check.property.CoverPercentage
import arrow.check.property.Coverage
import arrow.check.property.DiscardCount
import arrow.check.property.IconType
import arrow.check.property.Label
import arrow.check.property.Markup
import arrow.check.property.PropertyName
import arrow.check.property.ShrinkCount
import arrow.check.property.Size
import arrow.check.property.TestCount
import arrow.check.property.coverPercentage
import arrow.check.property.labelCovered
import arrow.core.AndThen
import arrow.core.Option
import arrow.core.extensions.list.foldable.foldMap
import arrow.core.identity
import arrow.core.toOption
import arrow.core.toT
import arrow.syntax.collections.tail
import arrow.typeclasses.Monoid
import pretty.AnsiStyle
import pretty.Color
import pretty.Doc
import pretty.PageWidth
import pretty.SimpleDoc
import pretty.SimpleDocF
import pretty.alterAnnotations
import pretty.annotate
import pretty.ansistyle.monoid.monoid
import pretty.color
import pretty.colorDull
import pretty.doc
import pretty.fill
import pretty.group
import pretty.hCat
import pretty.layoutPretty
import pretty.line
import pretty.nest
import pretty.nil
import pretty.plus
import pretty.spaced
import pretty.spaces
import pretty.symbols.bullet
import pretty.symbols.comma
import pretty.symbols.dot
import pretty.text
import pretty.toRawString
import pretty.vSep
import kotlin.math.floor
import kotlin.math.max

data class Report<out A>(
  val numTests: TestCount,
  val numDiscarded: DiscardCount,
  val coverage: Coverage<CoverCount>,
  val status: A
) {
    companion object
}

sealed class Progress {
    object Running : Progress()
    data class Shrinking(val report: FailureSummary) : Progress()
}

sealed class Result {
    object Success : Result()
    object GivenUp : Result()
    data class Failure(val summary: FailureSummary) : Result()

    companion object
}

data class FailureSummary(
  val usedSize: Size,
  val usedSeed: RandSeed,
  val numShrinks: ShrinkCount,
  val failureDoc: Doc<Markup>,
  val annotations: List<FailureAnnotation>,
  val footnotes: List<() -> Doc<Markup>>
) {
    companion object
}

sealed class FailureAnnotation {
    data class Input(val text: () -> Doc<Markup>) : FailureAnnotation()
    data class Annotation(val text: () -> Doc<Markup>) : FailureAnnotation()
}

data class Summary(
  val waiting: PropertyCount,
  // val running: PropertyCount, TODO readd once I add execPar
  val failed: PropertyCount,
  val gaveUp: PropertyCount,
  val successful: PropertyCount
) {
    companion object {
        fun monoid() = object : Monoid<Summary> {
            override fun empty(): Summary = Summary(
                PropertyCount(0),
                // PropertyCount(0),
                PropertyCount(0),
                PropertyCount(0),
                PropertyCount(0)
            )

            override fun Summary.combine(b: Summary): Summary = Summary(
                PropertyCount(waiting.unPropertyCount + b.waiting.unPropertyCount),
                // PropertyCount(running.unPropertyCount + b.running.unPropertyCount),
                PropertyCount(failed.unPropertyCount + b.failed.unPropertyCount),
                PropertyCount(gaveUp.unPropertyCount + b.gaveUp.unPropertyCount),
                PropertyCount(successful.unPropertyCount + b.successful.unPropertyCount)
            )
        }
    }
}

inline class PropertyCount(val unPropertyCount: Int)

data class ColumnWidth(
  val percentage: Int,
  val min: Int,
  val name: Int,
  val nameFail: Int
) {
    companion object {
        fun monoid(): Monoid<ColumnWidth> = object : Monoid<ColumnWidth> {
            override fun empty(): ColumnWidth = ColumnWidth(0, 0, 0, 0)
            override fun ColumnWidth.combine(b: ColumnWidth): ColumnWidth =
                ColumnWidth(
                    max(percentage, b.percentage),
                    max(min, b.min),
                    max(name, b.name),
                    max(nameFail, b.nameFail)
                )
        }
    }
}

// ------- Pretty printing
fun Option<PropertyName>.doc(): Doc<Markup> = fold({
    "<interactive>".text()
}, { s -> s.unPropertyName.doc() })

fun Report<Progress>.prettyProgress(name: Option<PropertyName>): Doc<Markup> = when (status) {
    is Progress.Running -> (bullet().annotate(Markup.Icon(IconType.Running)) spaced
            name.doc() spaced
            "passed".text() spaced
            numTests.testCount() +
            numDiscarded.discardCount() spaced
            "(running)".text()).annotate(Markup.Progress.Running) +
            coverage.ifNotEmpty { line() + prettyPrint(numTests) }
    is Progress.Shrinking -> ("↯".text().annotate(Markup.Icon(IconType.Shrinking)) spaced
            name.doc() spaced
            "failed after".text() spaced
            numTests.testCount() +
            numDiscarded.discardCount() spaced
            "(shrinking)".text()).annotate(Markup.Progress.Shrinking)
}

fun Report<Result>.prettyResult(name: Option<PropertyName>): Doc<Markup> = when (status) {
    is Result.Success -> ("✓".text().annotate(Markup.Icon(IconType.Success)) spaced
            name.doc() spaced
            "passed".text() spaced
            numTests.testCount() + dot()).annotate(Markup.Result.Success) +
            coverage.ifNotEmpty { line() + prettyPrint(numTests) }
    is Result.GivenUp -> ("⚐".text().annotate(Markup.Icon(IconType.GaveUp)) spaced
            name.doc() spaced
            "gave up after".text() spaced
            TestCount(numDiscarded.unDiscardCount).testCount() + comma() spaced
            "passed".text() spaced
            numTests.testCount() + dot()).annotate(Markup.Result.GaveUp) +
            coverage.ifNotEmpty { line() + prettyPrint(numTests) }
    is Result.Failure -> ("\uD83D\uDFAC".text().annotate(Markup.Icon(IconType.Failure)) spaced
            name.doc() spaced
            "failed after".text() spaced
            numTests.testCount() +
            numDiscarded.discardCount() spaced
            status.summary.numShrinks.shrinkCount() + dot()).annotate(Markup.Result.Failed) +
            coverage.ifNotEmpty { line() + prettyPrint(numTests) } line
            status.summary.pretty()
}

fun <A> Coverage<A>.ifNotEmpty(f: Coverage<A>.() -> Doc<Markup>): Doc<Markup> =
    if (unCoverage.isEmpty()) nil()
    else f(this)

fun Coverage<CoverCount>.prettyPrint(tests: TestCount): Doc<Markup> =
    unCoverage.toList().let {
        if (it.size == 1 && it.first().first.isEmpty()) it.first().second.let { v ->
            v.values.map { l ->
                l.pretty(tests, v.values.toList().width(tests))
            }.vSep()
        }
        else it.map { (k, v) ->
            "┏━━".text() spaced k.fold({ "<top>".text() }, { it.unLabelTable.text() }) line
                    v.values.map { l ->
                        "┃".text() spaced l.pretty(tests, v.values.toList().width(tests))
                    }.vSep()
        }.vSep()
    }

// TODO refractor duplicate code
fun Label<CoverCount>.pretty(tests: TestCount, width: ColumnWidth): Doc<Markup> {
    val covered = labelCovered(tests)
    val icon = if (covered) "  ".text() else "⚠ ".text().annotate(Markup.Icon(IconType.Coverage))
    val name = name.unLabelName.text().fill(width.name).let { if (!covered) it.annotate(Markup.Coverage) else it }
    val wmin = min.renderCoverPercentage().text().fill(width.min)
    val lmin = when {
        width.min == 0 -> nil()
        covered.not() -> "✗".text() spaced wmin
        min.unCoverPercentage == 0.0 -> "".text().fill(width.min)
        else -> ("✓".text() spaced wmin).annotate(Markup.Result.Success)
    }

    return icon spaced name spaced
            annotation.coverPercentage(tests).renderCoverPercentage().text().fill(6).let {
                if (!covered) it.annotate(Markup.Coverage) else it
            } spaced
            coverageBar(
                annotation.coverPercentage(tests),
                min
            ).let { if (!covered) it.annotate(Markup.Coverage) else it } spaced
            lmin.let { if (!covered) it.annotate(Markup.Coverage) else it }
}

fun coverageBar(p: CoverPercentage, min: CoverPercentage): Doc<Markup> {
    val barWidth = 20
    val coverageRatio = p.unCoverPercentage / 100.0
    val coverageWidth = floor(coverageRatio * barWidth).toInt()
    val minRatio = min.unCoverPercentage / 100.0
    val minWidth = floor(minRatio * barWidth).toInt()
    fun <A> List<A>.ind(): Int = floor(((coverageRatio * barWidth) - coverageWidth) * size).toInt()
    fun <A> List<A>.part() = get(ind())
    val fillWidth = barWidth - coverageWidth - 1
    val fillErrWidth = max(0, minWidth - coverageWidth - 1)
    val fillSurplusWidth = fillWidth - fillErrWidth
    fun bar(full: Char, parts: List<Char>): Doc<Markup> =
        listOf(
            (0..coverageWidth).joinToString("") { "$full" }.text(),
            if (fillWidth >= 0)
                if (parts.ind() == 0)
                    if (fillErrWidth > 0) parts.part().toString().text().annotate(Markup.Style.Failure)
                    else parts.part().toString().text().annotate(Markup.CoverageFill)
                else parts.part().toString().text()
            else nil(),
            (0..fillErrWidth).joinToString("") { "${parts.first()}" }.text().annotate(Markup.Style.Failure),
            (0..fillSurplusWidth).joinToString("") { "${parts.first()}" }.text().annotate(Markup.CoverageFill)
        ).hCat()
    return bar('█', listOf('·', '▏', '▎', '▍', '▌', '▋', '▊', '▉'))
}

fun List<Label<CoverCount>>.width(tests: TestCount): ColumnWidth = foldMap(ColumnWidth.monoid()) {
    it.width(tests)
}

fun Label<CoverCount>.width(tests: TestCount): ColumnWidth = ColumnWidth(
    percentage = annotation.coverPercentage(tests).renderCoverPercentage().length,
    min = if (min.unCoverPercentage == 0.0) 0 else min.renderCoverPercentage().length,
    name = name.unLabelName.length,
    nameFail = if (labelCovered(tests)) 0 else name.unLabelName.length
)

fun CoverPercentage.renderCoverPercentage(): String = "$unCoverPercentage%"

// TODO add reproduce notice
fun FailureSummary.pretty(): Doc<Markup> = annotations.prettyAnnotations().ifNotEmpty { vSep() + line() } +
        footnotes.map { it().annotate(Markup.Footnote) }.ifNotEmpty { vSep() + line() } +
        failureDoc

fun <A> List<Doc<A>>.ifNotEmpty(f: List<Doc<A>>.() -> Doc<A>): Doc<A> =
    if (isEmpty()) nil()
    else f(this)

fun List<FailureAnnotation>.prettyAnnotations(): List<Doc<Markup>> =
    if (size == 1) first().let { fst ->
        when (fst) {
            is FailureAnnotation.Annotation -> fst.text().group().annotate(Markup.Annotation)
            is FailureAnnotation.Input -> ("forAll".text() spaced "=".text() +
                    (line() + fst.text().annotate(Markup.Annotation)).nest(2)).group()
        }.let { listOf(it) }
    } else {
        val szLen = "$size".length
        fold(0 toT emptyList<Doc<Markup>>()) { (i, acc), v ->
            when (v) {
                is FailureAnnotation.Annotation -> i toT acc + v.text().group().annotate(Markup.Annotation)
                is FailureAnnotation.Input ->
                    (i + 1) toT acc + ("forAll".text() + (i + 1).doc().fill(szLen) spaced pretty.symbols.equals() +
                            (line() + v.text().annotate(Markup.Annotation)).nest(2)).group()
            }
        }.b
    }

fun TestCount.testCount(): Doc<Nothing> = unTestCount.plural("test".text(), "tests".text())

fun ShrinkCount.shrinkCount(): Doc<Nothing> = unShrinkCount.plural("shrink".text(), "shrinks".text())

fun DiscardCount.discardCount(): Doc<Nothing> =
    if (this.unDiscardCount == 0) nil()
    else " with".text() spaced this.unDiscardCount.doc() spaced "discarded".text()

fun <A> Int.plural(singular: Doc<A>, plural: Doc<A>): Doc<A> =
    if (this == 1) doc() spaced singular
    else doc() spaced plural

fun Doc<Markup>.render(useColor: UseColor): String = alterAnnotations {
    if (useColor == UseColor.EnableColor) when (it) {
        is Markup.Diff -> emptyList()
        is Markup.DiffAdded -> listOf(
            Style.Prefix("+", it.offset), Style.Ansi(colorDull(Color.Green))
        )
        is Markup.DiffRemoved -> listOf(
            Style.Prefix("-", it.offset), Style.Ansi(colorDull(Color.Red))
        )
        is Markup.Result.Failed -> listOf(Style.Ansi(color(Color.Red)))
        is Markup.Result.GaveUp -> listOf(Style.Ansi(colorDull(Color.Yellow)))
        is Markup.Result.Success -> listOf(Style.Ansi(colorDull(Color.Green)))
        is Markup.Progress.Shrinking -> listOf(Style.Ansi(color(Color.Red)))
        is Markup.Annotation -> listOf(Style.Ansi(colorDull(Color.Magenta)))
        is Markup.Icon -> when (it.name) {
            is IconType.Success -> listOf(Style.Ansi(colorDull(Color.Green)))
            is IconType.Shrinking -> listOf(Style.Ansi(color(Color.Red)))
            is IconType.GaveUp -> listOf(Style.Ansi(colorDull(Color.Yellow)))
            is IconType.Failure -> listOf(Style.Ansi(color(Color.Red)))
            is IconType.Coverage -> listOf(Style.Ansi(colorDull(Color.Yellow)))
            else -> emptyList()
        }
        is Markup.Coverage -> listOf(Style.Ansi(colorDull(Color.Yellow)))
        is Markup.CoverageFill -> listOf(Style.Ansi(color(Color.Black)))
        else -> emptyList()
    } else when (it) {
        is Markup.DiffAdded -> listOf(Style.Prefix("+", it.offset))
        is Markup.DiffRemoved -> listOf(Style.Prefix("-", it.offset))
        else -> emptyList()
    }
}
    .layoutPretty(PageWidth.Available(120, 0.5F))
    .renderMarkup()

fun Report<Progress>.renderProgress(useColor: UseColor, name: Option<PropertyName>): String =
    prettyProgress(name).render(useColor)

fun Report<Result>.renderResult(useColor: UseColor, name: Option<PropertyName>): String =
    prettyResult(name).render(useColor)

sealed class Style {
    data class Prefix(val pre: String, val col: Int) : Style()
    data class Ansi(val st: AnsiStyle) : Style()
}

// TODO this could be implemented with renderDecorated if that had nicer types
fun SimpleDoc<Style>.renderMarkup(): String {
    tailrec fun SimpleDoc<Style>.go(
      xs: List<Style>,
      cont: (String) -> String
    ): String = when (val dF = unDoc.value()) {
        is SimpleDocF.Fail -> throw IllegalStateException("Encountered Fail in doc render. Please report this!")
        is SimpleDocF.Nil -> cont("")
        is SimpleDocF.Line -> dF.doc.go(xs, AndThen(cont).compose { str ->
            // This is quite the hack, but it works ^^
            // Maybe implement it as filter and get all Style.Prefix if in the future there can be more than one
            xs.firstOrNull { it is Style.Prefix }.toOption().fold({
                "\n${spaces(dF.i)}$str"
            }, {
                (it as Style.Prefix)
                "\n${spaces(it.col)}${it.pre}${spaces(dF.i - it.col - 1)}$str"
            })
        })
        is SimpleDocF.Text -> dF.doc.go(xs, AndThen(cont).compose { dF.str + it })
        is SimpleDocF.AddAnnotation -> when (val a = dF.ann) {
            is Style.Prefix -> dF.doc.go(listOf(dF.ann) + xs, cont)
            is Style.Ansi -> {
                val currStyle = xs.firstOrNull { it is Style.Ansi }
                val newS = a.st + (currStyle as Style.Ansi).st
                dF.doc.go(listOf(Style.Ansi(newS)) + xs, AndThen(cont).compose {
                    newS.toRawString() + it
                })
            }
            else -> dF.doc.go(listOf(dF.ann) + xs, cont)
        }
        is SimpleDocF.RemoveAnnotation -> dF.doc.go(xs.tail(), AndThen(cont).compose { str ->
            if (xs.first() is Style.Ansi)
                xs.tail().firstOrNull { it is Style.Ansi }!!.let {
                    (it as Style.Ansi)
                    it.st.toRawString() + str
                }
            else str
        })
    }
    return go(listOf(Style.Ansi(AnsiStyle.monoid().empty())), ::identity)
}
