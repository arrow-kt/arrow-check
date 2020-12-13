package arrow.check.internal

sealed class AndThenS<in I, out O> {
    data class Single<I, O>(val f: suspend (I) -> O, val index: Int = 1) : AndThenS<I, O>() {
        override fun toString(): String = "Single(depth = $index)"
    }

    data class Join<I, O>(val fa: AndThenS<I, AndThenS<I, O>>) : AndThenS<I, O>() {
        override fun toString(): String = "Join($fa)"
    }

    data class Concat<I, P, O>(val left: AndThenS<I, P>, val right: AndThenS<P, O>) : AndThenS<I, O>() {
        override fun toString(): String = "Concat(left = $left, right = $right)"
    }

    fun <X> andThen(g: suspend (O) -> X): AndThenS<I, X> = when (this) {
        is Single ->
            if (index <= maxStackDepthSize) Single({ g(f(it)) }, index + 1)
            else andThenF(Single(g))
        else -> andThenF(Single(g))
    }

    fun <X> compose(g: suspend (X) -> I): AndThenS<X, O> = when (this) {
        is Single ->
            if (index <= maxStackDepthSize) Single({ f(g(it)) }, index + 1)
            else composeF(Single(g))
        else -> composeF(Single(g))
    }

    fun <X> andThenF(right: AndThenS<O, X>): AndThenS<I, X> = Concat(this, right)
    fun <X> composeF(right: AndThenS<X, I>): AndThenS<X, O> = Concat(right, this)

    suspend operator fun invoke(i: I): O = loop(this as AndThenS<Any?, Any?>, i, 0)

    @Suppress("UNCHECKED_CAST")
    private tailrec suspend fun loop(self: AndThenS<Any?, Any?>, current: Any?, joins: Int): O = when (self) {
        is Single -> if (joins == 0) self.f(current) as O else loop(
            self.f(current) as AndThenS<Any?, Any?>,
            current,
            joins - 1
        )
        is Join -> loop(
            self.fa.andThen { Concat(Single<Any?, Any?>({ current }), it) },
            current,
            joins + 1
        )
        is Concat<*, *, *> -> {
            when (val oldLeft = self.left) {
                is Single -> {
                    val left = oldLeft as Single<Any?, Any?>
                    val newSelf = self.right as AndThenS<Any?, Any?>
                    loop(newSelf, left.f(current), joins)
                }
                is Join<*, *>,
                is Concat<*, *, *> -> loop(
                    rotateAccumulate(self.left as AndThenS<Any?, Any?>, self.right as AndThenS<Any?, Any?>),
                    current, joins
                )
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private tailrec fun rotateAccumulate(
        left: AndThenS<Any?, Any?>,
        right: AndThenS<Any?, Any?>
    ): AndThenS<Any?, Any?> = when (left) {
        is Concat<*, *, *> -> rotateAccumulate(
            left.left as AndThenS<Any?, Any?>,
            (left.right as AndThenS<Any?, Any?>).andThenF(right)
        )
        is Join -> Join(left.fa.andThen { it.andThenF(right) })
        is Single -> left.andThenF(right)
    }

    companion object {
        operator fun <I, O> invoke(f: suspend (I) -> O): AndThenS<I, O> = Single(f)

        private const val maxStackDepthSize = 127
    }
}

internal fun <I, O, X> AndThenS<I, O>.flatMap(g: suspend (O) -> AndThenS<I, X>): AndThenS<I, X> = AndThenS.Join(andThen(g))
