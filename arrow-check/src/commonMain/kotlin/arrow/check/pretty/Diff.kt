package arrow.check.pretty

import arrow.check.property.Markup
import arrow.core.andThen
import arrow.core.tail
import parsley.parseOrNull
import pretty.Doc
import pretty.align
import pretty.alterAnnotations
import pretty.annotate
import pretty.column
import pretty.fill
import pretty.hardLine
import pretty.indent
import pretty.lineBreak
import pretty.nest
import pretty.plus
import pretty.spaced
import pretty.symbols.comma
import pretty.symbols.lBracket
import pretty.symbols.lParen
import pretty.symbols.rBracket
import pretty.symbols.rParen
import pretty.symbols.space
import pretty.text
import kotlin.math.min

internal sealed class ValueDiffF<out F> {
  // two values that are entirely different
  data class ValueD(val l: KValue, val r: KValue) : ValueDiffF<Nothing>()

  // a value was removed
  data class ValueDRemoved(val v: KValue) : ValueDiffF<Nothing>()

  // a value was added
  data class ValueDAdded(val v: KValue) : ValueDiffF<Nothing>()

  // a tuple that contains at least one diff value that is not Same
  data class TupleD<F>(val vals: List<F>) : ValueDiffF<F>()

  // a list that contains at least one diff value that is not Same
  data class ListD<F>(val vals: List<F>) : ValueDiffF<F>()

  // a record that contains at least one diff value that is not Same
  data class Record<F>(val conName: String, val props: List<Pair<String, F>>) : ValueDiffF<F>()

  // a cons, name(prop1, ..., propN), that contains at least one diff value that is not Same
  data class Cons<F>(val consName: String, val props: List<F>) : ValueDiffF<F>()

  // compared values were equal
  data class Same(val v: KValue) : ValueDiffF<Nothing>()

  inline fun <B> map(f: (F) -> B): ValueDiffF<B> = when (this) {
    is ValueD -> ValueD(l, r)
    is ValueDRemoved -> ValueDRemoved(v)
    is ValueDAdded -> ValueDAdded(v)
    is TupleD -> TupleD(vals.map(f))
    is ListD -> ListD(vals.map(f))
    is Record -> Record(conName, props.map { (rec, v) -> rec to f(v) })
    is Cons -> Cons(consName, props.map(f))
    is Same -> Same(v)
  }

  companion object
}

internal data class ValueDiff(val unDiff: ValueDiffF<ValueDiff>) {

  @OptIn(ExperimentalStdlibApi::class)
  fun <B> cata(f: (ValueDiffF<B>) -> B): B =
    DeepRecursiveFunction<ValueDiff, B> { v ->
      f(v.unDiff.map { callRecursive(it) })
    }(this)

  companion object
}

internal infix fun KValue.toDiff(other: KValue): ValueDiff = (this to other).let { (a, b) ->
  when {
    a == b -> ValueDiff(ValueDiffF.Same(a))
    a is KValue.Cons && b is KValue.Cons
      && a.name == b.name ->
      ValueDiff(ValueDiffF.Cons(a.name, a.props.diffOrderedLists(b.props)))
    a is KValue.Record && b is KValue.Record
      && a.name == b.name ->
      ValueDiff(ValueDiffF.Record(a.name, diffMaps(a.kv, b.kv)))
    a is KValue.KTuple && b is KValue.KTuple ->
      ValueDiff(ValueDiffF.TupleD(a.vals.diffOrderedLists(b.vals)))
    a is KValue.KList && b is KValue.KList ->
      ValueDiff(ValueDiffF.ListD(a.vals.diffOrderedLists(b.vals)))
    else -> ValueDiff(ValueDiffF.ValueD(a, b))
  }
}

internal fun diffMaps(
  a: List<Pair<String, KValue>>,
  b: List<Pair<String, KValue>>
): List<Pair<String, ValueDiff>> =
  a.toMap().let { aMap ->
    val diffOrAdd = b.map { (k, v) ->
      if (aMap.containsKey(k)) k to aMap.getValue(k).toDiff(v)
      else k to ValueDiff(ValueDiffF.ValueDAdded(v))
    }.toMap()

    val diffOrRemove = a.mapNotNull { (k, v) ->
      if (diffOrAdd.containsKey(k)) null
      else (k to ValueDiff(ValueDiffF.ValueDRemoved(v)))
    }

    (diffOrAdd.toList() + diffOrRemove).map { (k, v) -> k to v }
  }

// Myer's algorithm
// Based on https://github.com/github/semantic/blob/master/src/Diffing/Algorithm/SES.hs
//  and slightly adapted to kotlin (mainly the array lookup differences)
internal sealed class Edit {
  data class Remove(val a: KValue) : Edit()
  data class Add(val a: KValue) : Edit()
  data class Compare(val l: KValue, val r: KValue) : Edit()
}

internal data class Endpoint(val x: Int, val y: Int, val script: List<Edit>)

internal fun List<KValue>.diffOrderedLists(ls: List<KValue>): List<ValueDiff> {
  val (lArr, rArr) = this.toTypedArray() to ls.toTypedArray()
  val (m, n) = size to ls.size

  fun moveDownFrom(e: Endpoint): Endpoint = Endpoint(
    e.x,
    e.y + 1,
    rArr.safeGet(e.y)?.let { listOf(Edit.Add(it)) + e.script } ?: e.script
  )

  fun moveRightFrom(e: Endpoint): Endpoint = Endpoint(
    e.x + 1,
    e.y,
    lArr.safeGet(e.x)?.let { listOf(Edit.Remove(it)) + e.script } ?: e.script
  )

  fun <A, B> zipNullable(a: A?, b: B?): Pair<A, B>? = if (a != null && b != null) a to b else null

  fun slideFrom(e: Endpoint): Endpoint {
    val (l, r) = zipNullable(lArr.safeGet(e.x), rArr.safeGet(e.y)) ?: return e
    // call shallow diff here (only top level: type and conName)
    return if (l == r)
      slideFrom(Endpoint(e.x + 1, e.y + 1, listOf(Edit.Compare(l, r)) + e.script))
    else e
  }

  fun isComplete(e: Endpoint): Boolean = e.x >= m && e.y >= n

  fun searchToD(d: Int, arr: Array<Endpoint>): List<Edit> {
    val offset = d
    fun searchAlongK(k: Int): Endpoint = when (k) {
      -d -> moveDownFrom(arr[k + 1 + offset])
      d -> moveRightFrom(arr[k - 1 + offset])
      -m -> moveDownFrom(arr[k + 1 + offset])
      n -> moveRightFrom(arr[k - 1 + offset])
      else ->
        if (arr[k - 1 + offset].x < arr[k + 1 + offset].x) moveDownFrom(arr[k + 1 + offset])
        else moveRightFrom(arr[k - 1 + offset])
    }

    return (-d..d step 2)
      .filter { it in -m..n }
      .map(::searchAlongK andThen ::slideFrom)
      .let { endpoints ->
        endpoints.firstOrNull(::isComplete)?.let { (_, _, script) -> script }
          ?: searchToD(
            d + 1,
            endpoints.map { it.x - it.y + d + 1 to it }.toMap().let { m ->
              Array(d * 2 + 2) { ind -> m[ind] } as Array<Endpoint>
            }
          )
      }
  }

  val editScript = when {
    lArr.isEmpty() -> rArr.map { Edit.Add(it) }
    rArr.isEmpty() -> lArr.map { Edit.Remove(it) }
    else -> searchToD(0, Array(2) { Endpoint(0, -1, emptyList()) })
  }.reversed()

  fun Edit.toValueDiff(): ValueDiff = when (this) {
    is Edit.Add -> ValueDiff(ValueDiffF.ValueDAdded(a))
    is Edit.Remove -> ValueDiff(ValueDiffF.ValueDRemoved(a))
    is Edit.Compare -> l.toDiff(r)
  }

  return if (editScript.size >= 2) {
    val fst = editScript[0]
    val snd = editScript[1]
    if (fst is Edit.Remove && snd is Edit.Add) {
      listOf(
        ValueDiff(ValueDiffF.ValueD(fst.a, snd.a))
      ) + editScript.drop(2).map { it.toValueDiff() }
    } else editScript.map { it.toValueDiff() }
  } else editScript.map { it.toValueDiff() }
}

internal fun <T : Any> Array<T>.safeGet(i: Int): T? = when (i) {
  in (0 until size) -> get(i)
  else -> null
}

internal sealed class DiffType {
  object Removed : DiffType() // Identifies a removed element
  object Added : DiffType() // Identifies an added element
  object Same : DiffType()
}

/**
 * This is quite the hack: In order to get proper prefixes and colors I am abusing some facts about
 *  ValueDiffs. The catamorphism returns an annotation which spans to add to the document + the prefix where the
 *  newline (there has to be one for diffs) is added. This means the annotation spans both the newline
 *  and the doc with the changes. This is later used by the custom renderMarkup method to remove a space
 *  from the SimpleDoc.Line and insert a + or -. Because the resulting diff can be placed at any nested level
 *  itself we must also add a Markup.Diff annotation that has the current column offset around the entire diff.
 *  In the end this all allows diffs to be rendered properly anywhere.
 */
internal fun ValueDiff.toLineDiff(): Doc<DiffType> {
  return when (val diff = this@toLineDiff.unDiff) {
    // Treat top level value diffs special because the nested diff implementation assumes newlines that this does not have
    is ValueDiffF.ValueD ->
      // This is the only place where we need to manually add a prefix because this cannot
      //  elevate the annotation to the previous newline
      ("-".text() spaced diff.l.doc()).annotate(DiffType.Removed) +
        (hardLine() spaced diff.r.doc()).annotate(DiffType.Added)
    is ValueDiffF.Same -> diff.v.doc()
    else ->
      cata<Pair<DiffType, Doc<DiffType>>> {
        when (val vd = it) {
          is ValueDiffF.ValueD ->
            DiffType.Removed to (vd.l.doc() + (hardLine() + vd.r.doc()).annotate(DiffType.Added))
          // value is the same, use KValue's pretty printer
          is ValueDiffF.Same -> DiffType.Same to vd.v.doc()
          is ValueDiffF.ValueDAdded -> DiffType.Added to vd.v.doc()
          is ValueDiffF.ValueDRemoved -> DiffType.Removed to vd.v.doc()
          // everything below contains a diff, that's why custom tuple/list methods are used that are always vertical without group
          is ValueDiffF.TupleD -> DiffType.Same to vd.vals.tupledNested()
          is ValueDiffF.ListD -> DiffType.Same to vd.vals.listNested()
          is ValueDiffF.Cons -> DiffType.Same to (vd.consName.text() +
            vd.props
              .tupledNested()
              .align()
            )
          is ValueDiffF.Record -> {
            val max = min(10, vd.props.maxByOrNull { it.first.length }?.first?.length ?: 0)
            DiffType.Same to (vd.conName.text() +
              vd.props
                .map { (name, it) ->
                  val (pre, doc) = it
                  if (name.length > max)
                    DiffType.Same to (name.text() + (lineBreak().nest(max) spaced pretty.symbols.equals() spaced doc.align()).annotate(
                      pre
                    )).align()
                  else pre to (name.text().fill(max) spaced pretty.symbols.equals() spaced doc.align()).align()
                }
                .tupledNested()
                .align())
          }
        }
      }.second.indent(1) // save space for +/- // This also ensures the layout algorithm is optimal
  }
}

internal fun <A> List<Pair<A, Doc<A>>>.listNested(): Doc<A> =
  encloseSepVert(
    lBracket(),
    hardLine() + rBracket(),
    comma() + space()
  )

internal fun <A> List<Pair<A, Doc<A>>>.tupledNested(): Doc<A> =
  encloseSepVert(
    lParen(),
    hardLine() + rParen(),
    comma() + space()
  )

internal fun <A> List<Pair<A, Doc<A>>>.encloseSepVert(l: Doc<A>, r: Doc<A>, sep: Doc<A>): Doc<A> = when {
  isEmpty() -> l + r
  size == 1 -> l + first().let { (t, doc) -> (hardLine() + doc).annotate(t) }.align() + r
  else -> l + ((listOf((hardLine() + space() + space()) to this.first()) + this.tail().map { sep to it })
    .map { (a, b) -> b.first to (a + b.second.align()) }
    .let { xs ->
      if (xs.size == 1) xs.first().let { (ann, d) -> d.annotate(ann) }
      else xs.tail().fold(xs.first().let { (ann, d) -> d.annotate(ann) }) { acc, (ann, d) ->
        (acc + (hardLine() + d).annotate(ann))
      }
    } + r).align()
}

internal infix fun String.diff(str: String): ValueDiff {
  val lhs = rootParser.parseOrNull(this.toCharArray()) ?: KValue.RawString(this)
  val rhs = rootParser.parseOrNull(str.toCharArray()) ?: KValue.RawString(str)

  return lhs.toDiff(rhs)
}

internal fun ValueDiff.toDoc(): Doc<Markup> =
  column { cc ->
    toLineDiff().alterAnnotations {
      when (it) {
        is DiffType.Removed -> listOf(Markup.DiffRemoved(cc))
        is DiffType.Added -> listOf(Markup.DiffAdded(cc))
        is DiffType.Same -> emptyList()
      }
    }
  }.annotate(Markup.Diff)
