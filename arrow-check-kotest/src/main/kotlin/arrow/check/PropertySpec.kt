package arrow.check

import arrow.check.property.*
import arrow.core.Tuple2
import io.kotlintest.AbstractSpec
import io.kotlintest.TestType
import io.kotlintest.specs.IntelliMarker

abstract class AbstractPropertySpec(f: AbstractPropertySpec.() -> Unit = {}) : AbstractSpec() {
    init { f() }

    operator fun String.invoke(props: List<Tuple2<String, Property>>): Unit =
        addTestCase(
            this,
            {
                checkGroup(this@invoke, props)
                    .let {
                        if (it.not()) throw AssertionError("Some tests failed!")
                    }
            },
            defaultTestCaseConfig,
            TestType.Test
        )

    operator fun String.invoke(
        propertyConfig: PropertyConfig = PropertyConfig(),
        c: suspend PropertyTest.() -> Unit
    ): Unit =
        addTestCase(
            this,
            {
                checkReport(PropertyName(this@invoke), property(propertyConfig, c))
                    .toException()
            },
            defaultTestCaseConfig,
            TestType.Test
        )

    operator fun String.invoke(
        args: Config,
        propertyConfig: PropertyConfig = PropertyConfig(),
        c: suspend PropertyTest.() -> Unit
    ): Unit =
        addTestCase(
            this,
            {
                checkReport(
                    args,
                    PropertyName(this@invoke),
                    property(propertyConfig, c)
                )
                    .toException()
            },
            defaultTestCaseConfig,
            TestType.Test
        )

    operator fun String.invoke(f: Property): Unit =
        addTestCase(
            this,
            {
                checkReport(PropertyName(this@invoke), f)
                    .toException()
            },
            defaultTestCaseConfig,
            TestType.Test
        )

    operator fun String.invoke(args: Config, f: Property): Unit =
        addTestCase(
            this,
            {
                checkReport(args, PropertyName(this@invoke), f)
                    .toException()
            },
            defaultTestCaseConfig,
            TestType.Test
        )
}

// TODO add summary here
fun Report<Result>.toException(): Unit = when (status) {
    is Result.Success -> Unit
    is Result.GivenUp -> throw AssertionError("GaveUp!")
    is Result.Failure -> throw AssertionError("Failed!")
}

abstract class PropertySpec(f: AbstractPropertySpec.() -> Unit = {}) : AbstractPropertySpec(f), IntelliMarker
