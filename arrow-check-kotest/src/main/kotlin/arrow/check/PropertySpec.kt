package arrow.check

import arrow.check.gen.Gen
import arrow.check.property.Property
import arrow.check.property.PropertyConfig
import arrow.check.property.PropertyName
import arrow.check.property.Test
import arrow.check.property.property
import io.kotest.core.spec.DslDrivenSpec
import io.kotest.core.test.DescriptionName
import io.kotest.core.test.TestCaseConfig
import io.kotest.core.test.TestType

abstract class AbstractPropertySpec(f: AbstractPropertySpec.() -> Unit = {}) : DslDrivenSpec() {
  init {
    f()
  }

  override fun defaultTestCaseConfig(): TestCaseConfig {
    return super.defaultTestCaseConfig() ?: TestCaseConfig()
  }

  operator fun <A> String.invoke(
    gen: Gen<Any?, A>,
    propertyConfig: PropertyConfig = PropertyConfig.default(),
    c: suspend Test.(A) -> Unit
  ): Unit =
    addTest(
      DescriptionName.TestName(this, this, false, false),
      {
        checkReport(PropertyName(this@invoke), gen, property(propertyConfig, c))
          .toException()
      },
      defaultTestCaseConfig(),
      TestType.Test
    )

  operator fun <A> String.invoke(
    args: Config,
    gen: Gen<Any?, A>,
    propertyConfig: PropertyConfig = PropertyConfig.default(),
    c: suspend Test.(A) -> Unit
  ): Unit =
    addTest(
      DescriptionName.TestName(this, this, false, false),
      {
        checkReport(
          args,
          PropertyName(this@invoke),
          gen,
          property(propertyConfig, c)
        )
          .toException()
      },
      defaultTestCaseConfig(),
      TestType.Test
    )

  operator fun <A> String.invoke(gen: Gen<Any?, A>, f: Property<A>): Unit =
    addTest(
      DescriptionName.TestName(this, this, false, false),
      {
        checkReport(PropertyName(this@invoke), gen, f)
          .toException()
      },
      defaultTestCaseConfig(),
      TestType.Test
    )

  operator fun <A> String.invoke(args: Config, gen: Gen<Any?, A>, f: Property<A>): Unit =
    addTest(
      DescriptionName.TestName(this, this, false, false),
      {
        checkReport(args, PropertyName(this@invoke), gen, f)
          .toException()
      },
      defaultTestCaseConfig(),
      TestType.Test
    )
}

// TODO add summary here
fun Report<Result>.toException(): Unit = when (status) {
  is Result.Success -> Unit
  is Result.GivenUp -> throw AssertionError("GaveUp!")
  is Result.Failure -> throw AssertionError("Failed!")
}

abstract class PropertySpec(f: AbstractPropertySpec.() -> Unit = {}) : AbstractPropertySpec(f)
