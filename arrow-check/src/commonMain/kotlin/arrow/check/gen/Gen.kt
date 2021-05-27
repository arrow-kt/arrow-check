package arrow.check.gen

import arrow.check.internal.AndThenS
import arrow.check.internal.flatMap
import arrow.check.pretty.showPretty
import arrow.check.property.Size
import arrow.core.Const
import arrow.core.Either
import arrow.core.Ior
import arrow.core.NonEmptyList
import arrow.core.Predicate
import arrow.core.Validated
import arrow.core.andThen
import arrow.core.identity
import arrow.core.left
import arrow.core.right
import arrow.core.tail
import arrow.core.toMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flattenConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlin.random.Random

/**
 * A generator wraps a suspend function from ([RandSeed], [Size], [R]) to [Rose].
 *
 * Combinators from `arrow-check` are either present on [Gen.Companion] or directly on the generators themselves.
 *
 * This means every invocation creates a single result plus all shrunk versions of that result.
 * > A [Rose]-Tree is a combination of a result plus a [Flow] of [Rose]-Tree's which represent the shrinks.
 *
 * > The generator is suspend mostly to make use of suspend functions inside the property context possible, but there
 *  are also a possible few use-cases for suspend generators.
 *
 * If the generated [Rose] is null, it is treated as a discarded value and will be ignored by the test.
 *  Some combinators also skip discarded values such as [filter].
 *
 * The environment [R] is local state which can be passed down through generators. By using [local] it is possible
 *  to make generators stateful. This is similar to how a split [RandSeed] is passed down through [flatMap].
 */
class Gen<in R, out A> internal constructor(internal val runGen: AndThenS<Triple<RandSeed, Size, R>, Rose<A>?>) {
  companion object {
    internal operator fun <R, A> invoke(f: suspend (Triple<RandSeed, Size, R>) -> Rose<A>?): Gen<R, A> =
      Gen(AndThenS.Single(f))

    fun <A> just(a: A): Gen<Any?, A> = Gen { Rose(a) }
  }
}

/**
 * Run the generator with an environment [r], effectively removing the need to supply this environment later on.
 */
fun <R, A> Gen<R, A>.runEnv(r: R): Gen<Any?, A> = Gen(runGen.compose { (seed, size, _) -> Triple(seed, size, r) })

/**
 * Retrieve the current environment from the generator.
 */
fun <R> Gen.Companion.ask(): Gen<R, R> = Gen { (_, _, env) -> Rose(env) }

/**
 * Modify the current environment.
 *
 * This is, as the name suggests, a local change only. Only the generator this is performed on will get the new state.
 */
fun <R, A> Gen<R, A>.local(f: (R) -> R): Gen<R, A> =
  Gen(runGen.compose { (seed, size, env) -> Triple(seed, size, f(env)) })

/**
 * Map the generated values.
 *
 * This operation preserves shrinking.
 */
fun <R, A, B> Gen<R, A>.map(f: (A) -> B): Gen<R, B> = Gen(runGen.andThen { it?.map(f) })

/**
 * Map the entire [Rose] tree if the value is not discarded.
 *
 * Since this operation may change the entire [Rose] tree, it is up to [f] whether or not it preserves shrinking.
 */
fun <R, A, B> Gen<R, A>.mapRose(f: suspend (Rose<A>) -> Rose<B>?): Gen<R, B> = Gen(runGen.andThen { it?.let { f(it) } })

/**
 * Run two generators and combine their results.
 *
 * This combines two generators in a way that not only preserves shrinking but also properly shrinks both in parallel.
 *  In comparison sequential shrinking would first shrink one generator and then move onto the shrinks of the other one, but
 *  it does not come back to the first one if the second one successfully shrinks. Parallel shrinking on the other
 *  hand does come back and attempts to shrink both.
 *  This is in direct comparison to [flatMap] which cannot shrink in parallel, so if possible avoid [flatMap] as much
 *  as possible.
 *
 * @see Gen.Companion.mapN for a n-ary version of this function.
 */
fun <R, A, B, C> Gen<R, A>.map2(other: Gen<R, B>, f: (A, B) -> C): Gen<R, C> =
  Gen(
    AndThenS<Triple<RandSeed, Size, R>, Triple<Pair<RandSeed, RandSeed>, Size, R>> { (seed, size, env) ->
      Triple(seed.split(), size, env)
    }.andThenF(
      this@map2.runGen.compose<Triple<Pair<RandSeed, RandSeed>, Size, R>> { (lr, sz, env) ->
        Triple(lr.first, sz, env)
      }.flatMap { roseA ->
        other.runGen.compose<Triple<Pair<RandSeed, RandSeed>, Size, R>> { (lr, size, env) ->
          Triple(lr.second, size, env)
        }.andThen { roseB ->
          if (roseA == null || roseB == null) null
          else roseA.zip(roseB, f)
        }
      }
    )
  )

/**
 * Flatmap for generators.
 *
 * Using this function has two major downsides:
 * - It is necessarily sequential, especially regarding shrinking. This means during shrinking it will attempt to
 *  shrink the [this] first and then proceed with the shrinks from the result of [f]. It cannot go back to
 *  the initial generator and thus may lead to sub-par shrinking.
 * - Very long [flatMap] chains are not stacksafe. This may be worked around by suspending and thus
 *  rescheduling inside [flatMap], but that depends on the coroutine runner.
 *
 * If possible use [map2], [mapN] or [map] instead.
 */
fun <R, R1, A, B> Gen<R, A>.flatMap(f: (A) -> Gen<R1, B>): Gen<R1, B> where R1 : R =
  Gen { (seed, size, env) ->
    val (l, r) = seed.split()
    runGen(Triple(l, size, env))?.flatMap { a ->
      f(a).runGen(Triple(r, size, env))
    }
  }

/**
 * Low level constructor for [Gen]. It provides access to [RandSeed] and [Size].
 *
 * The generated values are not shrunk, shrinking can be added again by using [shrink].
 */
fun <A> Gen.Companion.generate(f: suspend (RandSeed, Size) -> A): Gen<Any?, A> =
  Gen { (seed, size) -> Rose(f(seed, size)) }

/**
 * Add shrunk values by generating shrinks from the current results of the generator.
 *
 * If there are already existing shrinks, the new ones will be added after the existing shrinks.
 */
fun <R, A> Gen<R, A>.shrink(f: (A) -> Sequence<A>): Gen<R, A> =
  Gen(runGen.andThen { it?.expand(f.andThen { it.asFlow() }) })

/**
 * Throw away layers of the shrink tree.
 *
 * @param n refers to how many layers of shrinking are kept. So if it is zero, the entire tree is thrown away.
 */
fun <R, A> Gen<R, A>.prune(n: Int = 0): Gen<R, A> = Gen(runGen.andThen { it?.prune(n) })

// size
/**
 * Create a generator with access to the [Size] parameter.
 */
fun <R, A> Gen.Companion.sized(f: (Size) -> Gen<R, A>): Gen<R, A> =
  generate { _, size -> f(size) }.flatMap { it }

/**
 * Modify the [Size] parameter of a generator.
 *
 * This operation preserves shrinking.
 *
 * @param f Modify [Size]. This has to result in a positive number and thus [scale] will throw should it return a negative number.
 */
fun <R, A> Gen<R, A>.scale(f: (Size) -> Size): Gen<R, A> =
  Gen(runGen.compose { (seed, size, env) ->
    val newSz = f(size)
    if (newSz.unSize < 0) throw IllegalArgumentException("Gen.scale Negative size")
    else Triple(seed, newSz, env)
  })

/**
 * Set the [Size] parameter of a generator.
 *
 * This operation preserves shrinking.
 *
 * @param sz New [Size] parameter. Has to be a positive number, otherwise this function will throw.
 */
fun <R, A> Gen<R, A>.resize(sz: Size): Gen<R, A> = scale { sz }

/**
 * Modify the [Size] using [golden].
 *
 * Very useful in recursive generators to create progressively smaller values and act as a recursion breaker once the
 *  [Size] goes low enough.
 *
 * This operation preserves shrinking.
 */
fun <R, A> Gen<R, A>.small(): Gen<R, A> = scale { it.golden() }

/**
 * Modify [Size] using the golden ratio.
 *
 * @see Gen.small A combinator which uses this function.
 */
fun Size.golden(): Size = Size((unSize * 0.61803398875).toInt())

// Generators
// Integral numbers
/**
 * Generate [Long]'s in [range] (inclusive, inclusive) with shrinking.
 *
 * This generator is shrunk towards the origin of the [Range].
 *
 * @see long_ For a version that does not shrink.
 */
fun Gen.Companion.long(range: Range<Long>): Gen<Any?, Long> =
  long_(range).shrink { it.shrinkTowards(range.origin) }

/**
 * Generate [Long]'s in [range] (inclusive, inclusive) without shrinking.
 *
 * @see long For a version that does shrink.
 */
fun Gen.Companion.long_(range: Range<Long>): Gen<Any?, Long> =
  generate { randSeed, size ->
    val (min, max) = range.bounds(size)
    if (min == max) min
    else if (max == Long.MAX_VALUE && min == Long.MIN_VALUE) randSeed.nextLong().first
    else if (max == Long.MAX_VALUE) randSeed.nextLong(min, max).first // Better solution?
    else randSeed.nextLong(min, max + 1).first
  }

/**
 * Generate [Long]'s in [range] (inclusive, inclusive) but use a kotlin [LongRange] over [Range] with shrinking.
 *
 * This generator is shrunk towards the origin of the [Range].
 *
 * @see long_ For a version that does not shrink.
 */
fun Gen.Companion.long(range: LongRange): Gen<Any?, Long> =
  long(Range.constant(range))

/**
 * Generate [Long]'s in [range] (inclusive, inclusive) but use a kotlin [LongRange] over [Range] without shrinking.
 *
 * @see long For a version that does shrink
 */
fun Gen.Companion.long_(range: LongRange): Gen<Any?, Long> =
  long_(Range.constant(range))

/**
 * Generate [Int]'s in [range] (inclusive, inclusive) with shrinking.
 *
 * This generator is shrunk towards the origin of the [Range].
 *
 * @see int_ For a version that does not shrink
 */
fun Gen.Companion.int(range: Range<Int>): Gen<Any?, Int> =
  long(range.map { it.toLong() }).map { it.toInt() }

/**
 * Generate [Int]'s in [range] (inclusive, inclusive) without shrinking.
 *
 * @see int For a version that does shrink
 */
fun Gen.Companion.int_(range: Range<Int>): Gen<Any?, Int> =
  long_(range.map { it.toLong() }).map { it.toInt() }

/**
 * Generate [Int]'s in [range] (inclusive, inclusive) but use a kotlin [IntRange] over [Range] with shrinking.
 *
 * This generator is shrunk towards the origin of the [Range].
 *
 * @see int_ For a version that does not shrink
 */
fun Gen.Companion.int(range: IntRange): Gen<Any?, Int> =
  int(Range.constant(range))

/**
 * Generate [Int]'s in [range] (inclusive, inclusive) but use a kotlin [IntRange] over [Range] without shrinking.
 *
 * @see int For a version that does shrink
 */
fun Gen.Companion.int_(range: IntRange): Gen<Any?, Int> =
  int_(Range.constant(range))

/**
 * Generate [Short]'s in [range] (inclusive, inclusive) with shrinking.
 *
 * This generator is shrunk towards the origin of the [Range].
 *
 * @see short_ For a version that does not shrink
 */
fun Gen.Companion.short(range: Range<Short>): Gen<Any?, Short> =
  long(range.map { it.toLong() }).map { it.toShort() }

/**
 * Generate [Short]'s in [range] (inclusive, inclusive) without shrinking.
 *
 * @see short For a version that does shrink
 */
fun Gen.Companion.short_(range: Range<Short>): Gen<Any?, Short> =
  long_(range.map { it.toLong() }).map { it.toShort() }

/**
 * Generate [Byte]'s in [range] (inclusive, inclusive) with shrinking.
 *
 * This generator is shrunk towards the origin of the [Range].
 *
 * @see byte For a version that does shrink
 */
fun Gen.Companion.byte(range: Range<Byte>): Gen<Any?, Byte> =
  long(range.map { it.toLong() }).map { it.toByte() }

/**
 * Generate [Byte]'s in [range] (inclusive, inclusive) without shrinking.
 *
 * @see byte For a version that does shrink
 */
fun Gen.Companion.byte_(range: Range<Byte>): Gen<Any?, Byte> =
  long_(range.map { it.toLong() }).map { it.toByte() }

// floating point numbers
/**
 * Generate [Double]'s in [range] (inclusive, exclusive) with shrinking.
 *
 * This generator shrinks towards the origin of the [Range].
 *
 * @see double_ For a version that does not shrink.
 */
fun Gen.Companion.double(range: Range<Double>): Gen<Any?, Double> =
  double_(range).shrink { it.shrinkTowards(range.origin) }

/**
 * Generate [Double]'s in [range] (inclusive, exclusive) without shrinking.
 *
 * @see double For a version that does shrink.
 */
fun Gen.Companion.double_(range: Range<Double>): Gen<Any?, Double> =
  generate { randSeed, size ->
    val (min, max) = range.bounds(size)
    if (min == max) min
    else randSeed.nextDouble(min, max).first
  }

/**
 * Generate [Float]'s in [range] (inclusive, exclusive) with shrinking.
 *
 * This generator shrinks towards the origin of the [Range].
 *
 * @see float_ For a version that does not shrink.
 */
fun Gen.Companion.float(range: Range<Float>): Gen<Any?, Float> =
  double(range.map { it.toDouble() }).map { it.toFloat() }

/**
 * Generate [Float]'s in [range] (inclusive, exclusive) without shrinking.
 *
 * @see float For a version that does shrink.
 */
fun Gen.Companion.float_(range: Range<Float>): Gen<Any?, Float> =
  double_(range.map { it.toDouble() }).map { it.toFloat() }

// boolean
/**
 * Generate [Boolean]'s with shrinking.
 *
 * Shrinking shrinks to `false`.
 *
 * @see bool_ For a version that does not shrink.
 */
fun Gen.Companion.bool(): Gen<Any?, Boolean> =
  bool_().shrink { if (it) sequenceOf(false) else emptySequence() }

/**
 * Generate [Boolean]'s without shrinking.
 *
 * @see bool For a version that does shrink.
 */
fun Gen.Companion.bool_(): Gen<Any?, Boolean> =
  generate { randSeed, _ -> randSeed.nextInt(0, 2).first == 0 }

// chars
/**
 * Generate [Char]'s in [range] (inclusive, inclusive) with shrinking.
 *
 * This generator shrinks towards the origin of the [Range]
 *
 * @see char_ For a version that does not shrink
 */
fun Gen.Companion.char(range: Range<Char>): Gen<Any?, Char> =
  long(range.map { it.toLong() }).map { it.toChar() }

/**
 * Generate [Char]'s in [range] (inclusive, inclusive) without shrinking.
 *
 * @see char For a version that does shrink
 */
fun Gen.Companion.char_(range: Range<Char>): Gen<Any?, Char> =
  long_(range.map { it.toLong() }).map { it.toChar() }

/**
 * Generate [Char]'s in [range] (inclusive, inclusive) with shrinking but uses a kotlin [CharRange].
 *
 * This generator shrinks towards the origin of the [Range]
 *
 * @see char_ For a version that does not shrink
 */
fun Gen.Companion.char(range: CharRange): Gen<Any?, Char> =
  char(Range.constant(range))

/**
 * Generate [Char]'s in [range] (inclusive, inclusive) without shrinking but uses a kotlin [CharRange].
 *
 * @see char For a version that does shrink
 */
fun Gen.Companion.char_(range: CharRange): Gen<Any?, Char> =
  char_(Range.constant(range))

/**
 * Generates a value within `'0'..'1'`
 */
fun Gen.Companion.binit(): Gen<Any?, Char> = char('0'..'1')

/**
 * Generates a value within `'0'..'7'`
 */
fun Gen.Companion.octit(): Gen<Any?, Char> = char('0'..'7')

/**
 * Generates a value within `'0'..'9'`
 */
fun Gen.Companion.digit(): Gen<Any?, Char> = char('0'..'9')

/**
 * Generates a hexadecimal value within `'0'..'1' or 'a'..'f' or 'A'..'F'`
 */
fun Gen.Companion.hexit(): Gen<Any?, Char> = choice(digit(), char('a'..'f'), char('A'..'F'))

/**
 * Generate a lowercase char within `'a'..'z'`
 */
fun Gen.Companion.lower(): Gen<Any?, Char> = char('a'..'z')

/**
 * Generate a uppercase char within `'A'..'Z'`
 */
fun Gen.Companion.upper(): Gen<Any?, Char> = char('A'..'Z')

/**
 * Generate a alphabetical char: `choice(lower(), upper())`
 *
 * @see upper
 * @see lower
 */
fun Gen.Companion.alpha(): Gen<Any?, Char> = choice(lower(), upper())

/**
 * Generate a alphanumerical char: `choice(alpha(), digit())`
 */
fun Gen.Companion.alphaNum(): Gen<Any?, Char> = choice(alpha(), digit())

/**
 * Generate any ascii char: `int(0..127).map { it.toChar() }`
 */
fun Gen.Companion.ascii(): Gen<Any?, Char> = int(0..127).map { it.toChar() }

/**
 * Generate any latin1 char: `int(0..255).map { it.toChar() }`
 */
fun Gen.Companion.latin1(): Gen<Any?, Char> = int(0..255).map { it.toChar() }

/**
 * Generate unicode chars excluding non-chars and invalid surrogates.
 */
fun Gen.Companion.unicode(): Gen<Any?, Char> {
  val s1 = (55296 to int(0..55295).map { it.toChar() })
  val s2 = (8190 to int(57344..65533).map { it.toChar() })
  val s3 = (1048576 to int(65536..1114111).map { it.toChar() })
  return frequency(s1, s2, s3)
}

/**
 * Generate any unicode char: `char(Char.MIN_VALUE..Char.MAX_VALUE)`
 */
fun Gen.Companion.unicodeAll(): Gen<Any?, Char> = char(Char.MIN_VALUE..Char.MAX_VALUE)

/**
 * Create a string from a char generator.
 *
 * The length will be within [range] (inclusive, inclusive).
 *
 * This generator shrinks towards empty strings.
 */
fun <R> Gen<R, Char>.string(range: Range<Int>): Gen<R, String> =
  list(range).map { it.joinToString("") }

/**
 * Create a string from a char generator, but use an [IntRange] for convenience.
 *
 * The length will be within [range] (inclusive, inclusive).
 *
 * This generator shrinks towards empty strings.
 */
fun <R> Gen<R, Char>.string(range: IntRange): Gen<R, String> = string(Range.constant(range))

// combinators
/**
 * Generate which always produces [a].
 *
 * This generator does not shrink.
 */
fun <A> Gen.Companion.constant(a: A): Gen<Any?, A> = just(a)

/**
 * Generate a value by choosing from [els].
 *
 * This generator shrinks towards the first element.
 *
 * The argument [els] needs to be non-empty.
 */
fun <A> Gen.Companion.element(vararg els: A): Gen<Any?, A> =
  if (els.isEmpty()) throw IllegalArgumentException("Gen.element used with no arguments")
  else int(Range.constant(0, els.size - 1)).map { els[it] }

/**
 * Generate a value by running one of the [gens].
 *
 * This generator shrinks towards the first element.
 *
 * The argument [gens] needs to be non-empty.
 */
fun <R, A> Gen.Companion.choice(vararg gens: Gen<R, A>): Gen<R, A> =
  if (gens.isEmpty()) throw IllegalArgumentException("Gen.Choice used with no arguments")
  else int(Range.constant(0, gens.size - 1)).flatMap { gens[it] }

/**
 * Generate a value by running one of the [gens].
 *
 * This combinator choose weighted based on the first part of the pair.
 *
 * This generator shrinks towards the first element.
 *
 * The argument [gens] needs to be non-empty.
 */
fun <R, A> Gen.Companion.frequency(vararg gens: Pair<Int, Gen<R, A>>): Gen<R, A> =
  if (gens.isEmpty()) throw IllegalArgumentException("Gens.Frequency used with no arguments")
  else {
    val total = gens.map { it.first }.sum()
    int(Range.constant(0, total)).flatMap { n ->
      gens.toList().pick(n)
    }
  }

private fun <A> List<Pair<Int, A>>.pick(n: Int): A =
  if (isEmpty()) throw IllegalArgumentException("Gen.Frequency.Pick used with no arguments")
  else first().let { (k, el) ->
    if (n <= k) el
    else tail().pick(n - k)
  }

/**
 * Helper to easily set up recursive generators.
 *
 * Chooses between non-recursive generators [nonRec] and recursive generators [rec] until the [Size] parameter
 *  becomes smaller or equal to 1. Each time [rec] gets chosen the [Size] gets shrunk by using [small].
 *
 * [rec] is also lazy in order to avoid stackoverflows on initialization.
 *
 * Without this helper, one has to manually break the recursion and that is very error prone.
 *
 * ```kotlin
 * sealed class Expr {
 *   data class Lit(val n: Int): Expr()
 *   data class Add(val l: Expr, val r: Expr): Expr()
 *   data class Invert(val exp: Expr): Expr()
 * }
 *
 * fun Gen.Companion.expr(): Gen<Any?, Expr> =
 *   recursive(
 *     listOf(Gen.int(0..100).map { Expr.Lit(it) })
 *   ) {
 *     listOf(
 *       expr().subtermN { e: Expr -> Expr.Invert(e) },
 *       expr().subtermN { l: Expr, r: Expr -> Expr.Add(l, r) }
 *     )
 *   }
 * ```
 */
fun <R, A> Gen.Companion.recursive(
  nonRec: List<Gen<R, A>>,
  rec: () -> List<Gen<R, A>>
): Gen<R, A> = sized { sz ->
  if (sz.unSize <= 1) choice(*nonRec.toTypedArray())
  else choice(*(nonRec + rec().map { it.small() }).toTypedArray())
}

/**
 * Create a generator that always discards its values.
 */
fun Gen.Companion.discard(): Gen<Any?, Nothing> = Gen { null }

/**
 * Ensure that a predicate holds for all values that this generate creates.
 *
 * It discards all values for which the predicate does not hold.
 *
 * This combinator preserves shrinking.
 */
fun <R, A> Gen<R, A>.ensure(predicate: Predicate<A>): Gen<R, A> =
  flatMap { (if (predicate(it)) Gen.just(it) else Gen.discard()) }

/**
 * Map over the generator and filter all null results.
 *
 * This generator retries until it gets a non-discarded value but it retries a maximum of 100 times before
 *  it itself discards.
 *
 * This combinator preserves shrinking.
 */
fun <R, A, B> Gen<R, A>.filterMap(f: (A) -> B?): Gen<R, B> {
  fun t(k: Int): Gen<R, B> =
    if (k > 100) Gen.discard()
    else scale { Size(2 * k + it.unSize) }.freeze().flatMap { (fst, gen) ->
      f(fst)?.let { gen.mapRose { it.filterMap(f) } } ?: t(k + 1)
    }
  return t(0)
}

/**
 * Filter the generator using the predicate.
 *
 * This is implemented using [filterMap] and thus has the same characteristics and thus differs from [ensure].
 *
 * This combinator preserves shrinking.
 *
 * @see filterMap
 */
fun <R, A> Gen<R, A>.filter(f: (A) -> Boolean): Gen<R, A> = filterMap {
  if (f(it)) it else null
}

/**
 * Generates `null` or [A].
 *
 * This combinator produces more values of type [A] with increasing [Size].
 */
fun <R, A> Gen<R, A>.orNull(): Gen<R, A?> = Gen.sized { sz ->
  Gen.frequency(
    2 to Gen.just(null) as Gen<R, A?>,
    1 + sz.unSize to this@orNull as Gen<R, A?>
  )
}

/**
 * Generate a [List] of [A]'s.
 *
 * The range used limits the size of the list.
 *
 * This generator will shrink towards an empty list and also shrink individual elements of the list.
 */
fun <R, A> Gen<R, A>.list(range: Range<Int>): Gen<R, List<A>> = Gen.sized { s ->
  Gen.int_(range).flatMap { n ->
    this@list.mapRose { Rose(it) }.replicate(n)
  }.mapRose { r ->
    r.flatMap {
      it.asSequence().interleave()
    }
  }
    .map { it.toList() }
    .ensure { it.size >= range.lowerBound(s) }
}

/**
 * Generate a [List] of [A]'s but uses an [IntRange] for convenience.
 */
fun <R, A> Gen<R, A>.list(range: IntRange): Gen<R, List<A>> =
  list(Range.constant(range))

internal fun <R, A> Gen<R, A>.replicate(n: Int): Gen<R, List<A>> =
  if (n <= 0) Gen.just(emptyList())
  else (0 until n).fold(Gen.just(emptyList<A>()) as Gen<R, List<A>>) { acc, _ ->
    acc.map2(this@replicate) { a, b -> a + b }
  }

internal fun <A> Sequence<A>.splits(): Sequence<Triple<Sequence<A>, A, Sequence<A>>> =
  firstOrNull()?.let { x ->
    sequenceOf(Triple(emptySequence<A>(), x, drop(1)))
      // flatMap for added laziness
      .flatMap {
        sequenceOf(it) + drop(1).splits().map { (a, b, c) ->
          Triple(sequenceOf(x) + a, b, c)
        }
      }
  } ?: emptySequence()

internal fun <A> Sequence<Rose<A>>.dropSome(): Sequence<Rose<Sequence<A>>> =
  toList().let { xs ->
    if (xs.isEmpty()) emptySequence()
    else iterate(xs.size) { it / 2 }.takeWhile { it > 0 }.flatMap { n -> xs.removes(n) }
  }.map { it.asSequence().interleave() }

internal fun <A> Sequence<Rose<A>>.shrinkOne(): Flow<Rose<Sequence<A>>> =
  splits().map { (xs, y, zs) -> // TODO Test with discarded ones
    y.shrinks.map { y1 -> (xs + sequenceOf(y1!!) + zs).interleave() }
  }.asFlow().flattenConcat()

internal fun <A> Sequence<Rose<A>>.interleave(): Rose<Sequence<A>> =
  Rose(
    this.map { it.res },
    dropSome().asFlow().onCompletion { emitAll(shrinkOne()) }
  )

/**
 * Generate a [Map] from a generator that generators key-value pairs.
 *
 * The range limits the size of the hashmap.
 *
 * This generator will shrink to an empty map and also shrink both keys and values.
 */
fun <R, K, A> Gen<R, Pair<K, A>>.hashMap(range: Range<Int>): Gen<R, Map<K, A>> = Gen.sized { s ->
  Gen.int_(range).flatMap { k ->
    this@hashMap.uniqueByKey(k)
  }
    .shrink { it.shrink() }
    .flatMap { it.sequence() }
    .map { it.toMap() }
    .ensure { it.size >= range.lowerBound(s) }
}

internal fun <R, A> List<Gen<R, A>>.sequence(): Gen<R, List<A>> =
  if (isEmpty()) Gen.just(emptyList())
  else {
    val (fst, tail) = uncons()
    fst.map2(tail.sequence()) { a, xs -> listOf(a) + xs }
  }

internal fun <R, K, A> Gen<R, Pair<K, A>>.uniqueByKey(n: Int): Gen<R, List<Gen<R, Pair<K, A>>>> {
  fun go(k: Int, map: Map<K, Gen<R, Pair<K, A>>>): Gen<R, List<Gen<R, Pair<K, A>>>> =
    if (k > 100) Gen.discard()
    else freeze().replicate(n).flatMap {
      val xs = it.map { (fst, sec) -> Pair(fst.first, sec) }.toMap()
        .toList().take(n - map.size).toMap()
      val res = map + xs
      if (res.size >= n) Gen.just(res.values.toList())
      else go(k + 1, res)
    }
  return go(0, emptyMap())
}

/**
 * Generate a [Set].
 *
 * The range limits the size of the hashmap.
 *
 * This generator will shrink to an empty set and also shrinks individual elements.
 */
fun <R, A> Gen<R, A>.set(range: Range<Int>): Gen<R, Set<A>> =
  map { it to Unit }.hashMap(range).map { it.keys }

// arrow combinators
/**
 * Generate an [Either]. This generates more [Either.Right] instances as the [Size] increases.
 *
 * This generator will preserve shrinking.
 *
 * @see either_ For a non weighted generator
 */
fun <R, L, A> Gen.Companion.either(lgen: Gen<R, L>, rgen: Gen<R, A>): Gen<R, Either<L, A>> =
  sized { s ->
    frequency(
      2 to lgen.map { it.left() },
      1 + s.unSize to rgen.map { it.right() }
    )
  }

/**
 * Generate an [Either] unweighted.
 *
 * This generator will preserve shrinking.
 *
 * @see either For a weighted generator
 */
fun <R, L, A> Gen.Companion.either_(lgen: Gen<R, L>, rgen: Gen<R, A>): Gen<R, Either<L, A>> =
  choice(
    lgen.map { it.left() },
    rgen.map { it.right() }
  )

/**
 * Generate a [Validated]. This generates more [Validated.Valid] instances as the [Size] increases.
 *
 * This generator will preserve shrinking.
 */
fun <R, E, A> Gen.Companion.validated(errGen: Gen<R, E>, succGen: Gen<R, A>): Gen<R, Validated<E, A>> =
  either(errGen, succGen).map { Validated.fromEither(it) }

/**
 * Generate an [Ior]. This generates more [Ior.Both] and [Ior.Right] instances as the [Size] increases.
 *
 * This generator will preserve shrinking.
 */
fun <R, L, A> Gen.Companion.ior(lgen: Gen<R, L>, rgen: Gen<R, A>): Gen<R, Ior<L, A>> =
  sized { s ->
    frequency(
      2 to lgen.map { Ior.Left(it) },
      1 + (s.unSize / 2) to lgen.map2(rgen) { l, r -> Ior.Both(l, r) },
      1 + s.unSize to rgen.map { Ior.Right(it) }
    )
  }

/**
 * Generate [Const] instances.
 *
 * This generator will preserve shrinking.
 */
fun <R, A, T> Gen<R, A>.const(): Gen<R, Const<A, T>> = map(::Const)

/**
 * Generate [NonEmptyList]'s within the [Range].
 *
 * > Since this generator uses [filterMap] and [list] it may discard should the range include elements below or
 *  equal to 0.
 *
 * This generator shrinks to a one element list and also shrinks the individual elements.
 */
fun <R, A> Gen<R, A>.nonEmptyList(range: Range<Int>): Gen<R, NonEmptyList<A>> =
  list(range).filterMap { NonEmptyList.fromList(it).orNull() }

// Subterms
/**
 * Freeze the inputs (Seed, Size and env) for a generator.
 *
 * This allow inspecting the value it will produce and is mostly used to implement advanced shrinking like in
 *  [subtermN] or the [hashMap] generator.
 *
 * The frozen generator preserves shrinking.
 */
fun <R, A> Gen<R, A>.freeze(): Gen<R, Pair<A, Gen<R, A>>> =
  Gen(runGen.andThen { it?.let { mx -> Rose(mx.res to Gen { mx }) } })

// Invariant: List size does not change
internal fun <R, A> List<Gen<R, A>>.genSubterms(): Gen<R, Subterms<A>> =
  map { it.freeze().map { it.second } }
    .sequence()
    .map { Subterms.All(it) as Subterms<Gen<R, A>> }
    .shrink { it.shrinkSubterms() }
    .flatMap {
      when (it) {
        is Subterms.One -> it.a.map { Subterms.One(it) }
        is Subterms.All -> it.l.sequence().map { Subterms.All(it) }
      }
    }

// invariant: size list in f is always equal to the size of this
internal fun <R, A> List<Gen<R, A>>.subtermList(f: (List<A>) -> Gen<R, A>): Gen<R, A> =
  genSubterms().flatMap { it.fromSubterms(f) }

/**
 * Create a generator from a subterm generator
 *
 * This allows shrinking not only the generator itself but also shrinking to one of its subterms.
 */
fun <R, A> Gen<R, A>.subtermN(f: suspend (A) -> A): Gen<R, A> =
  listOf(this).subtermList { Gen { _ -> Rose(f(it[0])) } }

/**
 * Create a generator from two subterm generator
 *
 * This allows shrinking not only the generator itself but also shrinking to one of its subterms.
 */
fun <R, A> Gen<R, A>.subtermN(f: suspend (A, A) -> A): Gen<R, A> =
  listOf(this, this).subtermList { Gen { _ -> Rose(f(it[0], it[1])) } }

/**
 * Create a generator from three subterm generator
 *
 * This allows shrinking not only the generator itself but also shrinking to one of its subterms.
 */
fun <R, A> Gen<R, A>.subtermN(f: suspend (A, A, A) -> A): Gen<R, A> =
  listOf(this, this, this).subtermList { Gen { _ -> Rose(f(it[0], it[1], it[2])) } }

internal sealed class Subterms<A> {
  data class One<A>(val a: A) : Subterms<A>()
  data class All<A>(val l: List<A>) : Subterms<A>()
}

internal fun <R, A> Subterms<A>.fromSubterms(f: (List<A>) -> Gen<R, A>): Gen<R, A> = when (this) {
  is Subterms.One -> Gen.just(a) as Gen<R, A>
  is Subterms.All -> f(l)
}

internal fun <A> Subterms<A>.shrinkSubterms(): Sequence<Subterms<A>> = when (this) {
  is Subterms.One -> emptySequence()
  is Subterms.All -> l.asSequence().map { Subterms.One(it) }
}

// permutation
/**
 * Generate sublists of a given list.
 *
 * This will shrink towards an empty list.
 */
fun <A> List<A>.subsequence(): Gen<Any?, List<A>> =
  map { a -> Gen.bool_().map { if (it) a else null } }
    .sequence()
    .map { it.mapNotNull(::identity) }
    .shrink { it.shrink() }

/**
 * Generate a random permutation of a given list.
 *
 * This will shrink towards the original list.
 */
fun <A> List<A>.shuffle(): Gen<Any?, List<A>> =
  if (isEmpty()) Gen.just(emptyList())
  else {
    Gen.int(Range.constant(0, size - 1)).flatMap { n ->
      val xs = toMutableList()
      val x = xs.removeAt(n)
      xs.toList().shuffle().map { listOf(x) + it }
    }
  }

// MapN boilerpalate
/**
 * n-ary composition of [map2]
 *
 * Since this is composition of [map2] it preserves parallel shrinking and should be preferred over [flatMap]!
 */
fun <R, A, B, C> Gen.Companion.mapN(g1: Gen<R, A>, g2: Gen<R, B>, f: (A, B) -> C): Gen<R, C> = g1.map2(g2, f)

fun <R, A, B, C, D> Gen.Companion.mapN(g1: Gen<R, A>, g2: Gen<R, B>, g3: Gen<R, C>, f: (A, B, C) -> D): Gen<R, D> =
  g1.map2(g2.map2(g3) { b, c -> b to c }) { a, (b, c) -> f(a, b, c) }

fun <R, A, B, C, D, E> Gen.Companion.mapN(
  g1: Gen<R, A>,
  g2: Gen<R, B>,
  g3: Gen<R, C>,
  g4: Gen<R, D>,
  f: (A, B, C, D) -> E
): Gen<R, E> =
  g1.map2(g2.map2(g3.map2(g4) { c, d -> c to d }) { b, cd -> b to cd }) { a, (b, cd) -> f(a, b, cd.first, cd.second) }

fun <R, A, B, C, D, E, F> Gen.Companion.mapN(
  g1: Gen<R, A>,
  g2: Gen<R, B>,
  g3: Gen<R, C>,
  g4: Gen<R, D>,
  g5: Gen<R, E>,
  f: (A, B, C, D, E) -> F
): Gen<R, F> =
  g1.map2(g2.map2(g3.map2(g4.map2(g5) { d, e -> d to e }) { c, de -> c to de }) { b, cde -> b to cde }) { a, (b, cde) ->
    f(a, b, cde.first, cde.second.first, cde.second.second)
  }

fun <R, A, B, C, D, E, F, G> Gen.Companion.mapN(
  g1: Gen<R, A>,
  g2: Gen<R, B>,
  g3: Gen<R, C>,
  g4: Gen<R, D>,
  g5: Gen<R, E>,
  g6: Gen<R, F>,
  f: (A, B, C, D, E, F) -> G
): Gen<R, G> =
  g1.map2(g2.map2(g3.map2(g4.map2(g5.map2(g6) { e, f -> e to f }) { d, ef -> d to ef }) { c, def -> c to def }) { b, cdef -> b to cdef }) { a, (b, cdef) ->
    val (c, def) = cdef
    val (d, ef) = def
    val (e, f2) = ef
    f(a, b, c, d, e, f2)
  }

fun <R, A, B, C, D, E, F, G, H> Gen.Companion.mapN(
  g1: Gen<R, A>,
  g2: Gen<R, B>,
  g3: Gen<R, C>,
  g4: Gen<R, D>,
  g5: Gen<R, E>,
  g6: Gen<R, F>,
  g7: Gen<R, G>,
  f: (A, B, C, D, E, F, G) -> H
): Gen<R, H> =
  g1.map2(g2.map2(g3.map2(g4.map2(g5.map2(g6.map2(g7) { f, g -> f to g }) { e, fg -> e to fg }) { d, efg -> d to efg }) { c, defg -> c to defg }) { b, cdefg -> b to cdefg }) { a, (b, cdefg) ->
    val (c, defg) = cdefg
    val (d, efg) = defg
    val (e, fg) = efg
    val (f2, g) = fg
    f(a, b, c, d, e, f2, g)
  }

fun <R, A, B, C, D, E, F, G, H, I> Gen.Companion.mapN(
  g1: Gen<R, A>,
  g2: Gen<R, B>,
  g3: Gen<R, C>,
  g4: Gen<R, D>,
  g5: Gen<R, E>,
  g6: Gen<R, F>,
  g7: Gen<R, G>,
  g8: Gen<R, H>,
  f: (A, B, C, D, E, F, G, H) -> I
): Gen<R, I> =
  g1.map2(g2.map2(g3.map2(g4.map2(g5.map2(g6.map2(g7.map2(g8) { g, h -> g to h }) { f, gh -> f to gh }) { e, fgh -> e to fgh }) { d, efgh -> d to efgh }) { c, defgh -> c to defgh }) { b, cdefgh -> b to cdefgh }) { a, (b, cdefgh) ->
    val (c, defgh) = cdefgh
    val (d, efgh) = defgh
    val (e, fgh) = efgh
    val (f2, gh) = fgh
    val (g, h) = gh
    f(a, b, c, d, e, f2, g, h)
  }

fun <R, A, B> Gen<R, A>.tupled(other: Gen<R, B>): Gen<R, Pair<A, B>> =
  map2(other, ::Pair)

fun <R, A, B> Gen.Companion.tupledN(first: Gen<R, A>, second: Gen<R, B>): Gen<R, Pair<A, B>> =
  first.map2(second, ::Pair)

fun <R, A, B, C> Gen.Companion.tupledN(first: Gen<R, A>, second: Gen<R, B>, third: Gen<R, C>): Gen<R, Triple<A, B, C>> =
  Gen.mapN(first, second, third) { a, b, c -> Triple(a, b, c) }

// Debugging generators
/**
 * Run a generator and return the first non discarded result
 */
suspend fun <R, A> Gen<R, A>.sample(size: Size = Size(30), r: R): A {
  tailrec suspend fun loop(n: Int): A =
    if (n <= 0) throw IllegalStateException("Gen.Sample too many discards")
    else {
      val seed = RandSeed(Random.nextLong())
      when (val res = this.runGen(Triple(seed, size, r))?.res) {
        null -> loop(n - 1)
        else -> res
      }
    }
  return loop(100)
}

suspend fun <A> Gen<Any?, A>.sample(size: Size = Size(30)): A = sample(size, Unit)

/**
 * Run a generator and print the first result together with its first layer of shrinks.
 */
suspend fun <R, A> Gen<R, A>.print(
  seed: RandSeed = RandSeed(Random.nextLong()),
  size: Size = Size(30),
  SA: (A) -> String = { it.toString() },
  env: R
): Unit {
  when (val rose = runGen(Triple(seed, size, env))) {
    null -> {
      println("=== Outcome ===")
      println("<discard>")
    }
    else -> {
      println("=== Outcome ===")
      println(rose.res.showPretty(SA))
      println("=== Shrinks ===")
      rose.shrinks.collect {
        if (it == null) println("<discard>")
        else println(it.res.showPretty(SA))
      }
    }
  }
}
