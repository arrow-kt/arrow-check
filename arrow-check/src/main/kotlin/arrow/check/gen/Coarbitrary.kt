package arrow.check.gen

import arrow.core.*

// @higherkind boilerplate
class ForCoarbitrary private constructor() {
    companion object
}
typealias CoarbitraryOf<A> = arrow.Kind<ForCoarbitrary, A>

@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
inline fun <A> CoarbitraryOf<A>.fix(): Coarbitrary<A> =
    this as Coarbitrary<A>

/**
 * Typeclass which abstracts the behaviour of changing a generator based on some input of type [A]
 *
 * This is used when generating functions to provide variance for the generator that produces the result of a function by using the input to vary the random number generator.
 */
interface Coarbitrary<A> : CoarbitraryOf<A> {

    /**
     * Transform a generator upon receiving an [A]. This usually ends up calling [variant] a bunch of times to change the random nubmer generator.
     */
    fun <M, B> GenT<M, B>.coarbitrary(a: A): GenT<M, B>

    companion object
}

/**
 * Change the seed of the random number generator. This is implemented in a way such that even similar inputs yield vastly different results.
 */
fun <M, B> GenT<M, B>.variant(i: Long): GenT<M, B> =
    GenT(AndThen(runGen).compose { (seed, size) -> seed.variant(i) toT size })

// instances
@extension
interface Tuple2Coarbitrary<A, B> : Coarbitrary<Tuple2<A, B>> {
    fun CA(): Coarbitrary<A>
    fun CB(): Coarbitrary<B>
    override fun <M, C> GenT<M, C>.coarbitrary(a: Tuple2<A, B>): GenT<M, C> =
        CA().run {
            CB().run {
                coarbitrary(a.a).coarbitrary(a.b)
            }
        }
}

fun <A, B> Tuple2.Companion.coarbitrary(CA: Coarbitrary<A>, CB: Coarbitrary<B>): Coarbitrary<Tuple2<A, B>> = object : Tuple2Coarbitrary<A, B> {
    override fun CA(): Coarbitrary<A> = CA
    override fun CB(): Coarbitrary<B> = CB
}

fun unitCoarbitrary(): Coarbitrary<Unit> = object : Coarbitrary<Unit> {
    override fun <M, B> GenT<M, B>.coarbitrary(a: Unit): GenT<M, B> = this
}

interface BooleanCoarbitrary: Coarbitrary<Boolean> {
    override fun <M, B> GenT<M, B>.coarbitrary(a: Boolean): GenT<M, B> =
        Either.coarbitrary(unitCoarbitrary(), unitCoarbitrary()).run {
            coarbitrary(if (a) Unit.left() else Unit.right())
        }
}
fun Boolean.Companion.coarbitrary(): Coarbitrary<Boolean> = object: BooleanCoarbitrary {}

interface LongCoarbitrary : Coarbitrary<Long> {
    override fun <M, B> GenT<M, B>.coarbitrary(a: Long): GenT<M, B> = variant(a)
}

fun Long.Companion.coarbitrary(): Coarbitrary<Long> = object: LongCoarbitrary {}

interface IntCoarbitrary : Coarbitrary<Int> {
    override fun <M, B> GenT<M, B>.coarbitrary(a: Int): GenT<M, B> = variant(a.toLong())
}

fun Int.Companion.coarbitrary(): Coarbitrary<Int> = object: IntCoarbitrary {}

interface ShortCoarbitrary : Coarbitrary<Short> {
    override fun <M, B> GenT<M, B>.coarbitrary(a: Short): GenT<M, B> = variant(a.toLong())
}

fun Short.Companion.coarbitrary(): Coarbitrary<Short> = object: ShortCoarbitrary {}

interface ByteCoarbitrary : Coarbitrary<Byte> {
    override fun <M, B> GenT<M, B>.coarbitrary(a: Byte): GenT<M, B> = variant(a.toLong())
}

fun Byte.Companion.coarbitrary(): Coarbitrary<Byte> = object: ByteCoarbitrary {}

interface FloatCoarbitrary : Coarbitrary<Float> {
    override fun <M, B> GenT<M, B>.coarbitrary(a: Float): GenT<M, B> = variant(a.toDouble().toRawBits())
}

fun Float.Companion.coarbitrary(): Coarbitrary<Float> = object: FloatCoarbitrary {}

interface DoubleCoarbitrary : Coarbitrary<Double> {
    override fun <M, B> GenT<M, B>.coarbitrary(a: Double): GenT<M, B> = variant(a.toRawBits())
}

fun Double.Companion.coarbitrary(): Coarbitrary<Double> = object: DoubleCoarbitrary {}

interface CharCoarbitrary : Coarbitrary<Char> {
    override fun <M, B> GenT<M, B>.coarbitrary(a: Char): GenT<M, B> = variant(a.toLong())
}

fun Char.Companion.coarbitrary(): Coarbitrary<Char> = object: CharCoarbitrary {}

interface StringCoarbitrary : Coarbitrary<String> {
    override fun <M, B> GenT<M, B>.coarbitrary(a: String): GenT<M, B> =
        ListK.coarbitrary(Char.coarbitrary()).run {
            coarbitrary(a.toList().k())
        }
}

fun String.Companion.coarbitrary(): Coarbitrary<String> = object: StringCoarbitrary {}

// @extension
interface ListKCoarbitrary<A> : Coarbitrary<ListK<A>> {
    fun AC(): Coarbitrary<A>
    override fun <M, B> GenT<M, B>.coarbitrary(a: ListK<A>): GenT<M, B> =
        a.foldLeft(variant(1)) { acc, v ->
            AC().run {
                acc.variant(2).coarbitrary(v)
            }
        }
}

fun <A> ListK.Companion.coarbitrary(CA: Coarbitrary<A>): Coarbitrary<ListK<A>> = object : ListKCoarbitrary<A> {
    override fun AC(): Coarbitrary<A> = CA
}

// @extension
interface NonEmptyListCoarbitrary<A> : Coarbitrary<NonEmptyList<A>> {
    fun AC(): Coarbitrary<A>
    override fun <M, B> GenT<M, B>.coarbitrary(a: Nel<A>): GenT<M, B> =
        ListK.coarbitrary(AC()).run {
            coarbitrary(a.all.k())
        }
}

fun <A> NonEmptyList.Companion.coarbitrary(CA: Coarbitrary<A>): Coarbitrary<NonEmptyList<A>> = object : NonEmptyListCoarbitrary<A> {
    override fun AC(): Coarbitrary<A> = CA
}

// @extension
interface OptionCoarbitrary<A> : Coarbitrary<Option<A>> {
    fun AC(): Coarbitrary<A>
    override fun <M, B> GenT<M, B>.coarbitrary(a: Option<A>): GenT<M, B> =
        a.fold({ variant(1) }, {
            AC().run {
                variant(2L).coarbitrary(it)
            }
        })
}

fun <A> Option.Companion.coarbitrary(CA: Coarbitrary<A>): Coarbitrary<Option<A>> = object : OptionCoarbitrary<A> {
    override fun AC(): Coarbitrary<A> = CA
}

// @extension
interface EitherCoarbitrary<L, R> : Coarbitrary<Either<L, R>> {
    fun LC(): Coarbitrary<L>
    fun RC(): Coarbitrary<R>
    override fun <M, B> GenT<M, B>.coarbitrary(a: Either<L, R>): GenT<M, B> =
        a.fold({
            LC().run { variant(1).coarbitrary(it) }
        }, {
            RC().run { variant(2).coarbitrary(it) }
        })
}

fun <L, R> Either.Companion.coarbitrary(LC: Coarbitrary<L>, RC: Coarbitrary<R>): Coarbitrary<Either<L, R>> = object : EitherCoarbitrary<L, R> {
    override fun LC(): Coarbitrary<L> = LC
    override fun RC(): Coarbitrary<R> = RC
}

// @extension
interface ConstCoarbitrary<A, T> : Coarbitrary<Const<A, T>> {
    fun AC(): Coarbitrary<A>
    override fun <M, B> GenT<M, B>.coarbitrary(a: Const<A, T>): GenT<M, B> =
        AC().run { coarbitrary(a.value()) }
}

fun <A, T> Const.Companion.coarbitrary(AC: Coarbitrary<A>): Coarbitrary<Const<A, T>> = object : ConstCoarbitrary<A, T> {
    override fun AC(): Coarbitrary<A> = AC
}

// @extension
interface IdCoarbitrary<A> : Coarbitrary<Id<A>> {
    fun AC(): Coarbitrary<A>
    override fun <M, B> GenT<M, B>.coarbitrary(a: Id<A>): GenT<M, B> =
        AC().run { coarbitrary(a.value()) }
}

fun <A> Id.Companion.coarbitrary(AC: Coarbitrary<A>): Coarbitrary<Id<A>> = object : IdCoarbitrary<A> {
    override fun AC(): Coarbitrary<A> = AC
}

// @extension
interface IorCoarbitrary<L, R> : Coarbitrary<Ior<L, R>> {
    fun LC(): Coarbitrary<L>
    fun RC(): Coarbitrary<R>
    override fun <M, B> GenT<M, B>.coarbitrary(a: Ior<L, R>): GenT<M, B> =
        a.fold({
            LC().run { variant(1).coarbitrary(it) }
        }, {
            RC().run { variant(2).coarbitrary(it) }
        }, { l, r ->
            LC().run {
                RC().run {
                    variant(3).coarbitrary(l).coarbitrary(r)
                }
            }
        })
}

fun <L, R> Ior.Companion.coarbitrary(LC: Coarbitrary<L>, RC: Coarbitrary<R>): Coarbitrary<Ior<L, R>> = object : IorCoarbitrary<L, R> {
    override fun LC(): Coarbitrary<L> = LC
    override fun RC(): Coarbitrary<R> = RC
}

// @extension
interface MapKCoarbitrary<K, V> : Coarbitrary<MapK<K, V>> {
    fun KC(): Coarbitrary<K>
    fun VC(): Coarbitrary<V>
    override fun <M, B> GenT<M, B>.coarbitrary(a: MapK<K, V>): GenT<M, B> =
        ListK.coarbitrary(Tuple2.coarbitrary(KC(), VC())).run {
            coarbitrary(a.toList().k().map { it.toTuple2() })
        }
}

fun <K, V> MapK.Companion.coarbitrary(KC: Coarbitrary<K>, VC: Coarbitrary<V>): Coarbitrary<MapK<K, V>> = object : MapKCoarbitrary<K, V> {
    override fun KC(): Coarbitrary<K> = KC
    override fun VC(): Coarbitrary<V> = VC
}

// @extension
interface SetKCoarbitrary<V> : Coarbitrary<SetK<V>> {
    fun VC(): Coarbitrary<V>
    override fun <M, B> GenT<M, B>.coarbitrary(a: SetK<V>): GenT<M, B> =
        ListK.coarbitrary(VC()).run {
            coarbitrary(a.toList().k())
        }
}

fun <V> SetK.Companion.coarbitrary(VC: Coarbitrary<V>): Coarbitrary<SetK<V>> = object : SetKCoarbitrary<V> {
    override fun VC(): Coarbitrary<V> = VC
}

// @extension
interface SequenceKCoarbitrary<A> : Coarbitrary<SequenceK<A>> {
    fun AC(): Coarbitrary<A>
    override fun <M, B> GenT<M, B>.coarbitrary(a: SequenceK<A>): GenT<M, B> =
        ListK.coarbitrary(AC()).run {
            coarbitrary(a.toList().k())
        }
}

fun <A> SequenceK.Companion.coarbitrary(AC: Coarbitrary<A>): Coarbitrary<SequenceK<A>> = object : SequenceKCoarbitrary<A> {
    override fun AC(): Coarbitrary<A> = AC
}

interface SortedMapKCoarbitrary<K: Comparable<K>, V> : Coarbitrary<SortedMapK<K, V>> {
    fun KC(): Coarbitrary<K>
    fun VC(): Coarbitrary<V>
    override fun <M, B> GenT<M, B>.coarbitrary(a: SortedMapK<K, V>): GenT<M, B> =
        ListK.coarbitrary(Tuple2.coarbitrary(KC(), VC())).run {
            coarbitrary(a.toList().k().map { it.toTuple2() })
        }
}

fun <K: Comparable<K>, V> SortedMapK.Companion.coarbitrary(KC: Coarbitrary<K>, VC: Coarbitrary<V>) =
    object: SortedMapKCoarbitrary<K, V> {
        override fun KC(): Coarbitrary<K> = KC
        override fun VC(): Coarbitrary<V> = VC
    }

// @extension
interface ValidatedCoarbitrary<E, A> : Coarbitrary<Validated<E, A>> {
    fun EC(): Coarbitrary<E>
    fun AC(): Coarbitrary<A>
    override fun <M, B> GenT<M, B>.coarbitrary(a: Validated<E, A>): GenT<M, B> =
        a.fold({
            EC().run {
                variant(1).coarbitrary(it)
            }
        }, {
            AC().run {
                variant(2).coarbitrary(it)
            }
        })
}

fun <E, A> Validated.Companion.coarbitrary(EC: Coarbitrary<E>, AC: Coarbitrary<A>): Coarbitrary<Validated<E, A>> = object : ValidatedCoarbitrary<E, A> {
    override fun AC(): Coarbitrary<A> = AC
    override fun EC(): Coarbitrary<E> = EC
}
