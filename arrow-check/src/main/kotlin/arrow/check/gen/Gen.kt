package arrow.check.gen

import arrow.check.property.Size
import arrow.core.*
import arrow.core.extensions.sequence.monadLogic.interleave
import arrow.syntax.collections.tail
import kotlinx.coroutines.flow.*
import kotlin.random.Random

class Gen<in R, A>(internal val runGen: suspend (Tuple3<RandSeed, Size, R>) -> Rose<A>?) {
    companion object {
        fun <A> just(a: A): Gen<Any?, A> = Gen { Rose(a) }
    }
}

fun <R, A> Gen<R, A>.runEnv(r: R): Gen<Any?, A> = Gen { (seed, size, _) -> runGen(Tuple3(seed, size, r)) }

fun <R> Gen.Companion.ask(): Gen<R, R> = Gen { (_, _, env) -> Rose(env) }

fun <R, A> Gen<R, A>.local(f: (R) -> R): Gen<R, A> = Gen { (seed, size, env) -> runGen(Tuple3(seed, size, f(env))) }

fun <R, A, B> Gen<R, A>.map(f: (A) -> B): Gen<R, B> = Gen { runGen(it)?.map(f) }

fun <R, A, B> Gen<R, A>.mapRose(f: suspend (Rose<A>) -> Rose<B>?): Gen<R, B> = Gen { runGen(it)?.let { f(it) } }

fun <R, A, B, C> Gen<R, A>.map2(other: Gen<R, B>, f: (A, B) -> C): Gen<R, C> =
    Gen { (seed, size, env) ->
        val (l, r) = seed.split()
        runGen(Tuple3(l, size, env))?.let { roseA ->
            other.runGen(Tuple3(r, size, env))?.let { roseB ->
                roseA.zip(roseB) { a, b -> f(a, b) }
            }
        }
    }

fun <R, R1, A, B> Gen<R, A>.flatMap(f: (A) -> Gen<R1, B>): Gen<R1, B> where R1: R =
    Gen { (seed, size, env) ->
        val (l, r) = seed.split()
        this@flatMap.runGen(Tuple3(l, size, env))?.flatMap { a ->
            f(a).let { genB ->
                genB.runGen(Tuple3(r, size, env))
            }
        }
    }

fun <A> Gen.Companion.generate(f: suspend (RandSeed, Size) -> A): Gen<Any?, A> =
    Gen { (seed, size) -> Rose(f(seed, size)) }

fun <R, A> Gen<R, A>.shrink(f: (A) -> Sequence<A>): Gen<R, A> = Gen { runGen(it)?.expand { it?.let(f) ?: emptySequence() } }

fun <R, A> Gen<R, A>.prune(n: Int = 0): Gen<R, A> = Gen { runGen(it)?.prune(n) }

// size
fun <R, A> Gen.Companion.sized(f: suspend (Size) -> Gen<R, A>): Gen<R, A> =
    generate { _, size -> f(size) }.flatMap { it }

fun <R, A> Gen<R, A>.scale(f: (Size) -> Size): Gen<R, A> = Gen { (seed, size, env) ->
    val newSz = f(size)
    if (newSz.unSize < 0) throw IllegalArgumentException("Gen.scale Negative size")
    else runGen(Tuple3(seed, size, env))
}

fun <R, A> Gen<R, A>.resize(sz: Size): Gen<R, A> = scale { sz }

fun <R, A> Gen<R, A>.small(): Gen<R, A> = scale(::golden)

fun golden(s: Size): Size = Size((s.unSize * 0.61803398875).toInt())

// Generators
// Integral numbers
fun Gen.Companion.long(range: Range<Long>): Gen<Any?, Long> =
    long_(range).shrink { it.shrinkTowards(range.origin) }

fun Gen.Companion.long_(range: Range<Long>): Gen<Any?, Long> =
    generate { randSeed, size ->
        val (min, max) = range.bounds(size)
        if (min == max) min
        else randSeed.nextLong(min, max).a
    }

fun Gen.Companion.long(range: LongRange): Gen<Any?, Long> =
    long(Range.constant(range))

fun Gen.Companion.long_(range: LongRange): Gen<Any?, Long> =
    long_(Range.constant(range))

fun Gen.Companion.int(range: Range<Int>): Gen<Any?, Int> =
    long(range.map { it.toLong() }).map { it.toInt() }

fun Gen.Companion.int_(range: Range<Int>): Gen<Any?, Int> =
    long_(range.map { it.toLong() }).map { it.toInt() }

fun Gen.Companion.int(range: IntRange): Gen<Any?, Int> =
    int(Range.constant(range))

fun Gen.Companion.int_(range: IntRange): Gen<Any?, Int> =
    int_(Range.constant(range))

fun Gen.Companion.short(range: Range<Short>): Gen<Any?, Short> =
    long(range.map { it.toLong() }).map { it.toShort() }

fun Gen.Companion.short_(range: Range<Short>): Gen<Any?, Short> =
    long_(range.map { it.toLong() }).map { it.toShort() }

fun Gen.Companion.byte(range: Range<Byte>): Gen<Any?, Byte> =
    long(range.map { it.toLong() }).map { it.toByte() }

fun Gen.Companion.byte_(range: Range<Byte>): Gen<Any?, Byte> =
    long_(range.map { it.toLong() }).map { it.toByte() }

// floating point numbers
fun Gen.Companion.double(range: Range<Double>): Gen<Any?, Double> =
    double_(range).shrink { it.shrinkTowards(range.origin) }

fun Gen.Companion.double_(range: Range<Double>): Gen<Any?, Double> =
    generate { randSeed, size ->
        val (min, max) = range.bounds(size)
        if (min == max) min
        else randSeed.nextDouble(min, max).a
    }

fun Gen.Companion.float(range: Range<Float>): Gen<Any?, Float> =
    double(range.map { it.toDouble() }).map { it.toFloat() }

fun Gen.Companion.float_(range: Range<Float>): Gen<Any?, Float> =
    double_(range.map { it.toDouble() }).map { it.toFloat() }

// boolean
fun Gen.Companion.bool(): Gen<Any?, Boolean> =
    bool_().shrink { if (it) sequenceOf(false) else emptySequence() }

fun Gen.Companion.bool_(): Gen<Any?, Boolean> =
    generate { randSeed, _ -> randSeed.nextInt(0, 2).a != 0 }

// chars
fun Gen.Companion.char(range: Range<Char>): Gen<Any?, Char> =
    long(range.map { it.toLong() }).map { it.toChar() }

fun Gen.Companion.char_(range: Range<Char>): Gen<Any?, Char> =
    long_(range.map { it.toLong() }).map { it.toChar() }

fun Gen.Companion.char(range: CharRange): Gen<Any?, Char> =
    char(Range.constant(range))

fun Gen.Companion.char_(range: CharRange): Gen<Any?, Char> =
    char_(Range.constant(range))

fun Gen.Companion.binit(): Gen<Any?, Char> = char('0'..'1')

fun Gen.Companion.octit(): Gen<Any?, Char> = char('0'..'7')

fun Gen.Companion.digit(): Gen<Any?, Char> = char('0'..'9')

fun Gen.Companion.hexit(): Gen<Any?, Char> = choice(digit(), char('a'..'f'), char('A'..'F'))

fun Gen.Companion.lower(): Gen<Any?, Char> = char('a'..'z')

fun Gen.Companion.upper(): Gen<Any?, Char> = char('A'..'Z')

fun Gen.Companion.alpha(): Gen<Any?, Char> = choice(lower(), upper())

fun Gen.Companion.alphaNum(): Gen<Any?, Char> = choice(alpha(), digit())

fun Gen.Companion.ascii(): Gen<Any?, Char> = int(0..127).map { it.toChar() }

fun Gen.Companion.latin1(): Gen<Any?, Char> = int(0..255).map { it.toChar() }

fun Gen.Companion.unicode(): Gen<Any?, Char> {
    val s1 = (55296 toT int(0..55295).map { it.toChar() })
    val s2 = (8190 toT int(57344..65533).map { it.toChar() })
    val s3 = (1048576 toT int(65536..1114111).map { it.toChar() })
    return frequency(s1, s2, s3)
}

fun Gen.Companion.unicodeAll(): Gen<Any?, Char> = char(Char.MIN_VALUE..Char.MAX_VALUE)

fun <R> Gen<R, Char>.string(range: Range<Int>): Gen<R, String> =
    list(range).map { it.joinToString("") }

fun <R> Gen<R, Char>.string(range: IntRange): Gen<R, String> = string(Range.constant(range))

// combinators
fun <A> Gen.Companion.constant(a: A): Gen<Any?, A> = just(a)

fun <A> Gen.Companion.element(vararg els: A): Gen<Any?, A> =
    if (els.isEmpty()) throw IllegalArgumentException("Gen.Element used with no arguments")
    else int(Range.constant(0, els.size - 1)).map { els[it] }

fun <R, A> Gen.Companion.choice(vararg gens: Gen<R, A>): Gen<R, A> =
    if (gens.isEmpty()) throw IllegalArgumentException("Gen.Choice used with no arguments")
    else int(Range.constant(0, gens.size - 1)).flatMap { gens[it] }

fun <R, A> Gen.Companion.frequency(vararg gens: Tuple2<Int, Gen<R, A>>): Gen<R, A> =
    if (gens.isEmpty()) throw IllegalArgumentException("Gens.Frequency used with no arguments")
    else {
        val total = gens.map { it.a }.sum()
        int(Range.constant(0, total)).flatMap { n ->
            gens.toList().pick(n)
        }
    }

private fun <A> List<Tuple2<Int, A>>.pick(n: Int): A =
    if (isEmpty()) throw IllegalArgumentException("Gen.Frequency.Pick used with no arguments")
    else first().let { (k, el) ->
        if (n <= k) el
        else tail().pick(n - k)
    }

fun <R, A> Gen.Companion.recursive(
    nonRec: List<Gen<R, A>>,
    rec: () -> List<Gen<R, A>>
): Gen<R, A> = sized { sz ->
    if (sz.unSize <= 1) choice(*nonRec.toTypedArray())
    else choice(*(nonRec + rec().map { it.small() }).toTypedArray())
}

fun <R, A> Gen.Companion.discard(): Gen<R, A> = Gen { null }

fun <R, A> Gen<R, A>.ensure(predicate: Predicate<A>): Gen<R, A> =
    flatMap { (if (predicate(it)) Gen.just(it) else Gen.discard<R, A>()) as Gen<R, A> }

fun <R, A, B> Gen<R, A>.filterMap(f: (A) -> B?): Gen<R, B> {
    fun t(k: Int): Gen<R, B> =
        if (k > 100) Gen.discard<R, B>()
        else scale { Size(2 * k + it.unSize) }.freeze().flatMap { (fst, gen) ->
            f(fst)?.let { gen.mapRose { it.filterMap(f) } } ?: t(k + 1)
        }
    return t(0)
}

fun <R, A> Gen<R, A>.filter(f: (A) -> Boolean): Gen<R, A> = filterMap {
    if (f(it)) it else null
}

fun <R, A> Gen<R, A>.orNull(): Gen<R, A?> = Gen.sized { sz ->
    Gen.frequency(
        2 toT Gen.just(null) as Gen<R, A?>,
        1 + sz.unSize toT this@orNull as Gen<R, A?>
    )
}


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

fun <R, A> Gen<R, A>.list(range: IntRange): Gen<R, List<A>> =
    list(Range.constant(range))

internal fun <R, A> Gen<R, A>.replicate(n: Int): Gen<R, List<A>> =
    if (n <= 0) Gen.just(emptyList<A>()) as Gen<R, List<A>>
    else (0..n).toList().fold(Gen.just(emptyList<A>()) as Gen<R, List<A>>) { acc, _ ->
        acc.map2(this@replicate) { a, b -> a + b }
    }

internal fun <A> Sequence<A>.splits(): Sequence<Tuple3<Sequence<A>, A, Sequence<A>>> =
    firstOrNull().toOption().fold({
        emptySequence()
    }, { x ->
        sequenceOf(Tuple3(emptySequence<A>(), x, drop(1)))
            // flatMap for added laziness
            .flatMap {
                sequenceOf(it) + drop(1).splits().map { (a, b, c) ->
                    Tuple3(sequenceOf(x) + a, b, c)
                }
            }
    })

internal fun <A> Sequence<Rose<A>>.dropOne(): Sequence<Rose<Sequence<A>>> =
    splits().map { (xs, _, zs) -> (xs + zs).interleave() }

internal fun <A> Sequence<Rose<A>>.shrinkOne(): Flow<Rose<Sequence<A>>> =
    splits().map { (xs, y, zs) -> // TODO Test with discarded ones
        y.shrinks.map { y1 -> (xs + sequenceOf(y1!!) + zs).interleave() }
    }.asFlow().flattenConcat()

internal fun <A> Sequence<Rose<A>>.interleave(): Rose<Sequence<A>> =
    Rose(
        this.map { it.res },
        dropOne().asFlow().onCompletion { emitAll(shrinkOne()) }
    )

fun <R, K, A> Gen<R, Tuple2<K, A>>.hashMap(range: Range<Int>): Gen<R, Map<K, A>> = Gen.sized { s ->
    Gen.int_(range).flatMap { k ->
        this@hashMap.uniqueByKey(k)
    }
        .shrink { it.shrink() }
        .flatMap { it.sequence() }
        .map { it.toMap() }
        .ensure { it.size >= range.lowerBound(s) }
}

internal fun <R, A> List<Gen<R, A>>.sequence(): Gen<R, List<A>> =
    if (isEmpty()) Gen.just(emptyList<A>()) as Gen<R, List<A>>
    else {
        val (fst, tail) = uncons()
        fst.map2(tail.sequence()) { a, xs -> listOf(a) + xs }
    }

internal fun <R, K, A> Gen<R, Tuple2<K, A>>.uniqueByKey(n: Int): Gen<R, List<Gen<R, Tuple2<K, A>>>> {
    fun go(k: Int, map: Map<K, Gen<R, Tuple2<K, A>>>): Gen<R, List<Gen<R, Tuple2<K, A>>>> =
        if (k > 100) Gen.discard()
        else freeze().replicate(n).flatMap {
            val res = (map + it.map { it.bimap({ it.a }, ::identity) }.toMap())
            if (res.size >= n) Gen.just(res.values.toList()) as Gen<R, List<Gen<R, Tuple2<K, A>>>>
            else go(k + 1, res)
        }
    return go(0, emptyMap())
}

fun <R, A> Gen<R, A>.set(range: Range<Int>): Gen<R, Set<A>> =
    map { it toT Unit }.hashMap(range).map { it.keys }

// arrow combinators
fun <R, L, A> Gen.Companion.either(lgen: Gen<R, L>, rgen: Gen<R, A>): Gen<R, Either<L, A>> =
    sized { s ->
        frequency(
            2 toT lgen.map { it.left() },
            1 + s.unSize toT rgen.map { it.right() }
        )
    }

fun <R, E, A> Gen.Companion.validated(errGen: Gen<R, E>, succGen: Gen<R, A>): Gen<R, Validated<E, A>> =
    either(errGen, succGen).map { Validated.fromEither(it) }

fun <R, L, A> Gen.Companion.ior(lgen: Gen<R, L>, rgen: Gen<R, A>): Gen<R, Ior<L, A>> =
    sized { s ->
        frequency(
            2 toT lgen.map { Ior.Left(it) },
            1 + (s.unSize / 2) toT lgen.map2(rgen) { l, r -> Ior.Both(l, r) },
            1 + s.unSize toT rgen.map { Ior.Right(it) }
        )
    }

fun <R, A, T> Gen<R, A>.const(): Gen<R, Const<A, T>> = map(::Const)

fun <R, A> Gen<R, A>.nonEmptyList(range: Range<Int>): Gen<R, NonEmptyList<A>> =
    list(range).filterMap { NonEmptyList.fromList(it).orNull() }

// Subterms. Overcoming the limits of flatMap
fun <R, A> Gen<R, A>.freeze(): Gen<R, Tuple2<A, Gen<R, A>>> =
    Gen {
        runGen(it)?.let { mx ->
            Rose(mx.res toT Gen<R, A> { mx })
        }
    }

// Invariant: List size does not change
internal fun <R, A> List<Gen<R, A>>.genSubterms(): Gen<R, Subterms<A>> =
    map { it.freeze().map { it.b } }
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

fun <R, A> Gen<R, A>.subtermGen(f: (A) -> Gen<R, A>): Gen<R, A> =
    listOf(this).subtermList { f(it.first()) }

fun <R, A> Gen<R, A>.subterm(f: (A) -> A): Gen<R, A> =
    subtermGen { Gen.just(f(it)) as Gen<R, A> }

// TODO Add others

// permutation
/*
fun <A> List<A>.subsequence(): Gen<Any?, List<A>> =
    map { a -> Gen.bool_().map { if (it) a else null } }
        .sequence()
        .map { it.mapNotNull(::identity) }
        .shrink { it.shrink() }
*/
sealed class Subterms<A> {
    data class One<A>(val a: A) : Subterms<A>()
    data class All<A>(val l: List<A>) : Subterms<A>()
}

fun <R, A> Subterms<A>.fromSubterms(f: (List<A>) -> Gen<R, A>): Gen<R, A> = when (this) {
    is Subterms.One -> Gen.just(a) as Gen<R, A>
    is Subterms.All -> f(l)
}

fun <A> Subterms<A>.shrinkSubterms(): Sequence<Subterms<A>> = when (this) {
    is Subterms.One -> emptySequence()
    is Subterms.All -> l.asSequence().map { Subterms.One(it) }
}

// samples // No point for IO imo as these are debug only anyway
// if you need them in IO just wrap them in IO { ... }
suspend fun <R, A> Gen<R, A>.sample(r: R): A {
    tailrec suspend fun loop(n: Int): A =
        if (n <= 0) throw IllegalStateException("Gen.Sample too many discards")
        else {
            val seed = RandSeed(Random.nextLong())
            when (val res = this.runGen(Tuple3(seed, Size(30), r))?.res) {
                null -> loop(n - 1)
                else -> res
            }
        }
    return loop(100)
}
