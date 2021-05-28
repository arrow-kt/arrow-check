package arrow.check.internal

internal sealed class AndThen<in I, out O> {
  data class Single<I, O>(val f: (I) -> O, val index: Int = 1) : AndThen<I, O>() {
    override fun toString(): String = "Single(depth = $index)"
  }

  data class Join<I, O>(val fa: AndThen<I, AndThen<I, O>>) : AndThen<I, O>() {
    override fun toString(): String = "Join($fa)"
  }

  data class Concat<I, P, O>(val left: AndThen<I, P>, val right: AndThen<P, O>) : AndThen<I, O>() {
    override fun toString(): String = "Concat(left = $left, right = $right)"
  }

  fun <X> andThen(g: (O) -> X): AndThen<I, X> = when (this) {
    is Single ->
      if (index <= maxStackDepthSize) Single({ g(f(it)) }, index + 1)
      else andThenF(Single(g))
    else -> andThenF(Single(g))
  }

  fun <X> compose(g: (X) -> I): AndThen<X, O> = when (this) {
    is Single ->
      if (index <= maxStackDepthSize) Single({ f(g(it)) }, index + 1)
      else composeF(Single(g))
    else -> composeF(Single(g))
  }

  fun <X> andThenF(right: AndThen<O, X>): AndThen<I, X> = Concat(this, right)
  fun <X> composeF(right: AndThen<X, I>): AndThen<X, O> = Concat(right, this)

  operator fun invoke(i: I): O = loop(this as AndThen<Any?, Any?>, i, 0)

  @Suppress("UNCHECKED_CAST")
  private tailrec fun loop(self: AndThen<Any?, Any?>, current: Any?, joins: Int): O = when (self) {
    is Single -> if (joins == 0) self.f(current) as O else loop(
      self.f(current) as AndThen<Any?, Any?>,
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
          val newSelf = self.right as AndThen<Any?, Any?>
          loop(newSelf, left.f(current), joins)
        }
        is Join<*, *>,
        is Concat<*, *, *> -> loop(
          rotateAccumulate(self.left as AndThen<Any?, Any?>, self.right as AndThen<Any?, Any?>),
          current, joins
        )
      }
    }
  }

  @Suppress("UNCHECKED_CAST")
  private tailrec fun rotateAccumulate(
    left: AndThen<Any?, Any?>,
    right: AndThen<Any?, Any?>
  ): AndThen<Any?, Any?> = when (left) {
    is Concat<*, *, *> -> rotateAccumulate(
      left.left as AndThen<Any?, Any?>,
      (left.right as AndThen<Any?, Any?>).andThenF(right)
    )
    is Join -> Join(left.fa.andThen { it.andThenF(right) })
    is Single -> left.andThenF(right)
  }

  companion object {
    operator fun <I, O> invoke(f: (I) -> O): AndThen<I, O> = Single(f)

    private const val maxStackDepthSize = 127
  }
}
