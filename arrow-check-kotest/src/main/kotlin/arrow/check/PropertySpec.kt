package arrow.check

import arrow.check.property.Property
import arrow.check.property.PropertyConfig
import arrow.check.property.PropertyName
import arrow.check.property.PropertyTest
import arrow.check.property.property
import arrow.core.Tuple2
import io.kotest.core.spec.DslDrivenSpec
import io.kotest.core.test.DescriptionName
import io.kotest.core.test.TestCaseConfig
import io.kotest.core.test.TestType

abstract class AbstractPropertySpec(f: AbstractPropertySpec.() -> Unit = {}) : DslDrivenSpec() {
    init { f() }

    override fun defaultTestCaseConfig(): TestCaseConfig {
        return super.defaultTestCaseConfig() ?: TestCaseConfig()
    }

    operator fun String.invoke(vararg props: Tuple2<String, Property>): Unit =
        addTest(
            DescriptionName.TestName(this, this, false, false),
            {
                checkGroup(this@invoke, *props)
                    .let {
                        if (it.not()) throw AssertionError("Some tests failed!")
                    }
            },
            defaultTestCaseConfig(),
            TestType.Test
        )

    operator fun String.invoke(
        propertyConfig: PropertyConfig = PropertyConfig.default(),
        c: suspend PropertyTest.() -> Unit
    ): Unit =
        addTest(
            DescriptionName.TestName(this, this, false, false),
            {
                checkReport(PropertyName(this@invoke), property(propertyConfig, c))
                    .toException()
            },
            defaultTestCaseConfig(),
            TestType.Test
        )

    operator fun String.invoke(
        args: Config,
        propertyConfig: PropertyConfig = PropertyConfig.default(),
        c: suspend PropertyTest.() -> Unit
    ): Unit =
        addTest(
            DescriptionName.TestName(this, this, false, false),
            {
                checkReport(
                    args,
                    PropertyName(this@invoke),
                    property(propertyConfig, c)
                )
                    .toException()
            },
            defaultTestCaseConfig(),
            TestType.Test
        )

    operator fun String.invoke(f: Property): Unit =
        addTest(
            DescriptionName.TestName(this, this, false, false),
            {
                checkReport(PropertyName(this@invoke), f)
                    .toException()
            },
            defaultTestCaseConfig(),
            TestType.Test
        )

    operator fun String.invoke(args: Config, f: Property): Unit =
        addTest(
            DescriptionName.TestName(this, this, false, false),
            {
                checkReport(args, PropertyName(this@invoke), f)
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
