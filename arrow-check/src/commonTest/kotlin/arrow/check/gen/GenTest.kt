package arrow.check.gen

import arrow.check.PropertySpec
import arrow.check.property.Property
import arrow.check.property.PropertyConfig
import arrow.check.property.annotate
import arrow.check.property.assert
import arrow.check.property.cover
import arrow.check.property.coverTable
import arrow.core.Either
import arrow.core.Ior
import arrow.core.Nel
import arrow.core.Validated
import pretty.text

class GenTest : PropertySpec({

  // Functor laws

  // Applicative laws

  // Monad laws

  // Alternative laws (with nullable ?: as orElse)

  // MonadPlus laws

  // MonadReader laws

  // Combinators and builders not covered above

  // Individual generators
  "Gen.long_ with arbitrary range"(
    Gen.mapN(
      Gen.long(Long.MIN_VALUE..Long.MAX_VALUE),
      Gen.long(Long.MIN_VALUE..Long.MAX_VALUE)
    ) { a, b ->
      if (a >= b) b to a
      else a to b
    }.map { (min, max) -> Range.constant(min, max) }
      .flatMap { range ->
        Gen.tupledN(Gen.sized { Gen.just(it) }, Gen.long_(range), Gen.just(range))
      },
    PropertyConfig.default() + PropertyConfig.testLimit(10000)
  ) { (currSize, l, range) ->
    val (min, max) = range.bounds(currSize)
    assert(l >= min) { "long_: Generated long was smaller than min".text() }
    assert(l < max) { "long_: Generated long was larger than max".text() }
  }

  "Gen.long_ with small range"(Property(Gen.long_(-100L..100)) { l: Long ->
    cover(0.1, "Min", l == -100L)
    cover(0.1, "Max", l == 100L)

    assert(l >= -100) { "long_: Generated long was smaller than min".text() }
    assert(l <= 100) { "long_: Generated long was larger than max".text() }
  }.verifiedTermination())

  "Gen.double_ with arbitrary range"(
    Gen.mapN(
      Gen.double(Range.constant(Double.MIN_VALUE, Double.MAX_VALUE)),
      Gen.double(Range.constant(Double.MIN_VALUE, Double.MAX_VALUE))
    ) { a, b ->
      if (a >= b) b to a
      else a to b
    }.map { (min, max) -> Range.constant(min, max) }
      .flatMap { range ->
        Gen.tupledN(Gen.just(range), Gen.sized { Gen.just(it) }, Gen.double_(range))
      }
  ) { (range, currSize, d) ->
    val (min, max) = range.bounds(currSize)
    assert(d >= min) { "double_: Generated double was smaller than min".text() }
    assert(d < max) { "double_: Generated double was larger than max".text() }
  }

  "Gen.double_ with small range"(Gen.double_(Range.constant(-100.0, 100.0))) { d ->
    assert(d >= -100.0) { "double_: Generated double was smaller than min".text() }
    assert(d < 100.0) { "double_: Generated double was larger than max".text() }
  }

  "Gen.bool_"(Property(Gen.bool_()) { b: Boolean ->
    cover(45.0, "true", b)
    cover(45.0, "false", b.not())
  }.verifiedTermination())

  "Gen.binit"(Property(Gen.binit()) { c: Char ->
    cover(45.0, "0", '0' == c)
    cover(45.0, "1", '1' == c)

    assert(c == '0' || c == '1') { "Char $c was not in range".text() }
  }.verifiedTermination())

  "Gen.octit"(Property(Gen.octit()) { c: Char ->
    for (c1 in '0'..'7') {
      cover(10.0, "$c1", c1 == c)
    }

    assert(c in '0'..'7') { "Char $c was not in range".text() }
  }.verifiedTermination())

  "Gen.digit"(Property(Gen.digit()) { c: Char ->
    for (c1 in '0'..'9') {
      cover(8.0, "$c1", c1 == c)
    }

    assert(c in '0'..'9') { "Char $c was not in range".text() }
  }.verifiedTermination())

  "Gen.hexit"(Property(Gen.hexit()) { c: Char ->
    for (c1 in '0'..'9') {
      cover(3.0, "$c1", c1 == c)
    }
    for (c1 in 'a'..'f') {
      cover(3.0, "$c1", c1 == c)
    }
    for (c1 in 'A'..'F') {
      cover(3.0, "$c1", c1 == c)
    }

    assert(c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F') { "Char $c was not in range".text() }
  }.verifiedTermination())

  "Gen.lower"(Property(Gen.lower()) { c: Char ->
    for (c1 in 'a'..'z') {
      cover(2.0, "$c1", c1 == c)
    }

    assert(c in 'a'..'z') { "Char $c was not in range".text() }
  }.verifiedTermination())

  "Gen.upper"(Property(Gen.upper()) { c: Char ->
    for (c1 in 'A'..'Z') {
      cover(2.0, "$c1", c1 == c)
    }

    assert(c in 'A'..'Z') { "Char $c was not in range".text() }
  }.verifiedTermination())

  "Gen.alpha"(Gen.alpha()) { c ->
    assert(c in 'a'..'z' || c in 'A'..'Z') { "Char $c was not in range".text() }
  }

  "Gen.alphaNum"(Gen.alphaNum()) { c ->
    assert(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') { "Char $c was not in range".text() }
  }

  "Gen.ascii"(Gen.ascii()) { c ->
    assert(c.toInt() in 0..127) { "Char $c was not in range".text() }
  }

  "Gen.latin1"(Gen.latin1()) { c ->
    assert(c.toInt() in 0..255) { "Char $c was not in range".text() }
  }

  "Gen.string"(Property(Gen.digit().string(0..20)) { str: String ->
    for (i in 0..20) {
      coverTable("Size", 3.0, "$i", i == str.length)
    }

    assert(str.length in 0..20) { "Size was outside of range".text() }
    assert(str.isEmpty() || str.all { it in '0'..'9' }) { "Contained wrong char".text() }
  }.verifiedTermination())

  "Gen.constant"(Gen.int(0..100).flatMap { Gen.tupledN(Gen.just(it), Gen.constant(it)) }) { (l, res) ->
    l.eqv(res)
  }

  "Gen.element"(Property(Gen.element(*(0..10).toList().toTypedArray())) { res: Int ->

    for (i in 0..10) cover(8.0, "$i", i == res)

    assert(res in 0..10) { "Element $res was not in range".text() }
  }.verifiedTermination())

  "Gen.choice"(Property(Gen.choice(*(0..10).map { Gen.constant(it) }.toTypedArray())) { res: Int ->

    for (i in 0..10) cover(8.0, "$i", i == res)

    assert(res in 0..10) { "Element $res was not in range".text() }
  }.verifiedTermination())

  // TODO Gen.frequency

  // TODO Gen.recursive

  // TODO Gen.discard, filter and such

  "Gen.orNull"(Property(Gen.int(0..100).orNull()) { a: Int? ->

    cover(8.0, "null", a == null)
    cover(85.0, "not null", a != null)
  }.verifiedTermination())

  "Gen.either"(Property(Gen.either(Gen.int(0..100), Gen.int(0..100))) { a: Either<Int, Int> ->

    cover(8.0, "left", a.isLeft())
    cover(85.0, "right", a.isRight())
  }.verifiedTermination())

  "Gen.either_"(Property(Gen.either_(Gen.int(0..100), Gen.int(0..100))) { a: Either<Int, Int> ->

    cover(45.0, "left", a.isLeft())
    cover(45.0, "right", a.isRight())
  }.verifiedTermination())

  "Gen.list"(Property(Gen.int(0..100).list(0..20)) { a: List<Int> ->

    for (i in 0..20) cover(3.0, "$i", a.size == i)

    assert(a.size in 0..20) { "Generated list is outside of allowed size".text() }
    assert(a.isEmpty() || a.all { it in 0..100 }) { "Generated list contained elements outside of the inner gens range".text() }
  }.verifiedTermination())

  "Gen.hashMap"(Property(Gen.int(0..100).tupled(Gen.alphaNum()).hashMap(Range.constant(0..20))) { ma: Map<Int, Char> ->

    for (i in 0..20) cover(3.0, "$i", ma.size == i)

    assert(ma.size in 0..20) { "Generated HashMap is outside of allowed size".text() }
    assert(
      ma.isEmpty() ||
        ma.keys.all { it in 0..100 } ||
        ma.values.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }
    ) { "Generated HashMap contained keys or values outside of range".text() }
  }.verifiedTermination())

  "Gen.set"(Property(Gen.int(0..100).set(Range.constant(0..20))) { a: Set<Int> ->
    for (i in 0..20) cover(3.0, "$i", a.size == i)

    assert(a.size in 0..20) { "Generated set is outside of allowed size. ${a.size}".text() }
    assert(a.isEmpty() || a.all { it in 0..100 }) { "Generated set contained elements outside of the inner gens range".text() }
  }.verifiedTermination())

  "Gen.validation"(Property(Gen.validated(Gen.int(0..100), Gen.int(0..100))) { a: Validated<Int, Int> ->

    cover(8.0, "invalid", a.isInvalid)
    cover(85.0, "valid", a.isValid)
  }.verifiedTermination())

  "Gen.ior"(Property(Gen.ior(Gen.int(0..100), Gen.int(0..100))) { a: Ior<Int, Int> ->
    cover(5.0, "Left", a.isLeft)
    cover(25.0, "Both", a.isBoth)
    cover(55.0, "Right", a.isRight)
  }.verifiedTermination())

  "Gen.nonEmptyList"(Property(Gen.int(0..100).nonEmptyList(Range.constant(1..21))) { a: Nel<Int> ->
    for (i in 1..21) cover(3.0, "$i", a.size == i)

    assert(a.size in 1..21) { "Generated list is outside of range".text() }
    assert(a.all { it in 0..100 }) { "Generated list contained values outside of range".text() }
  }.verifiedTermination())

  "Gen.subsequence"(Gen.int(0..100).list(0..20).flatMap {
    Gen.tupledN(Gen.just(it), it.subsequence())
  }) { (xs, ys) ->
    assert(ys.all { xs.contains(it) }) { "All elements of ys are in xs".text() }
  }

  "Gen.shuffle"(Gen.int(0..100).list(0..20).flatMap {
    Gen.tupledN(Gen.just(it), it.shuffle())
  }) { (xs, ys) ->
    annotate { "Sorted lists need to be the same".text() }
    xs.sorted().eqv(ys.sorted())
  }
})
