package com.airbnb.skipper

import javax.inject.Inject
import javax.inject.Named
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SimpleInjectorTest {
    interface Service

    class ServiceImpl : Service

    class AnotherService

    @Suppress("unused")
    class Target {
        @Inject
        var service: Service? = null

        @Inject
        var anotherService: AnotherService? = null

        var unbound: String? = null
    }

    @Suppress("unused")
    open class Parent {
        @Inject
        var service: Service? = null
    }

    @Suppress("unused")
    class Child : Parent() {
        @Inject
        var anotherService: AnotherService? = null
    }

    class NoArgClass

    class NoNoArgConstructor(required: String)

    @Test
    fun instanceBinding_returnsSameInstance() {
        val impl = ServiceImpl()
        val injector = SimpleInjector.builder().bind(ServiceImpl::class.java, impl).build()

        assertThat(injector.getInstance(ServiceImpl::class.java)).isSameAs(impl)
        assertThat(injector.getInstance(ServiceImpl::class.java)).isSameAs(impl)
    }

    @Test
    fun providerBinding_returnsNewInstanceEachTime() {
        val injector =
            SimpleInjector.builder().bindProvider(ServiceImpl::class.java) { ServiceImpl() }.build()

        val first = injector.getInstance(ServiceImpl::class.java)
        val second = injector.getInstance(ServiceImpl::class.java)
        assertThat(first).isNotNull()
        assertThat(second).isNotNull()
        assertThat(first).isNotSameAs(second)
    }

    @Test
    fun providerBinding_injectMembersCallsProviderPerField() {
        val injector =
            SimpleInjector.builder()
                .bindProvider(AnotherService::class.java) { AnotherService() }
                .build()

        val target = Target()
        injector.injectMembers(target)

        assertThat(target.anotherService).isNotNull()
    }

    @Test
    fun getInstance_fallsBackToNoArgConstructor() {
        val injector = SimpleInjector.builder().build()

        val instance = injector.getInstance(NoArgClass::class.java)

        assertThat(instance).isNotNull()
    }

    @Test
    fun getInstance_throwsWhenNoBindingAndNoNoArgConstructor() {
        val injector = SimpleInjector.builder().build()

        assertThatThrownBy { injector.getInstance(NoNoArgConstructor::class.java) }
            .isInstanceOf(InjectionException::class.java)
            .hasMessageContaining("Failed to create instance")
    }

    @Test
    fun register_instanceAfterBuild() {
        val injector = SimpleInjector.builder().build()
        val svc = AnotherService()

        injector.register(AnotherService::class.java, svc)

        assertThat(injector.getInstance(AnotherService::class.java)).isSameAs(svc)
    }

    @Test
    fun registerProvider_afterBuild() {
        val injector = SimpleInjector.builder().build()

        injector.registerProvider(AnotherService::class.java) { AnotherService() }

        val first = injector.getInstance(AnotherService::class.java)
        val second = injector.getInstance(AnotherService::class.java)
        assertThat(first).isNotSameAs(second)
    }

    @Test
    fun injectMembers_setsFieldsByExactType() {
        val svc = AnotherService()
        val injector = SimpleInjector.builder().bind(AnotherService::class.java, svc).build()

        val target = Target()
        injector.injectMembers(target)

        assertThat(target.anotherService).isSameAs(svc)
    }

    @Test
    fun injectMembers_setsFieldsByAssignableType() {
        val impl = ServiceImpl()
        val injector = SimpleInjector.builder().bind(ServiceImpl::class.java, impl).build()

        val target = Target()
        injector.injectMembers(target)

        assertThat(target.service).isSameAs(impl)
    }

    @Test
    fun injectMembers_leavesUnboundFieldsNull() {
        val injector = SimpleInjector.builder().build()

        val target = Target()
        injector.injectMembers(target)

        assertThat(target.service).isNull()
        assertThat(target.anotherService).isNull()
        assertThat(target.unbound).isNull()
    }

    @Test
    fun injectMembers_injectsInheritedFields() {
        val impl = ServiceImpl()
        val another = AnotherService()
        val injector =
            SimpleInjector.builder()
                .bind(ServiceImpl::class.java, impl)
                .bind(AnotherService::class.java, another)
                .build()

        val child = Child()
        injector.injectMembers(child)

        assertThat(child.service).isSameAs(impl)
        assertThat(child.anotherService).isSameAs(another)
    }

    @Test
    fun builder_multipleBindingsAllAvailable() {
        val impl = ServiceImpl()
        val another = AnotherService()
        val injector =
            SimpleInjector.builder()
                .bind(ServiceImpl::class.java, impl)
                .bind(AnotherService::class.java, another)
                .build()

        val target = Target()
        injector.injectMembers(target)

        assertThat(target.service).isSameAs(impl)
        assertThat(target.anotherService).isSameAs(another)
    }

    // --- @Named qualifier tests ---

    @Suppress("unused")
    class NamedTarget {
        // `@field:Named` is required so the qualifier lands on the backing field that
        // SimpleInjector reflects over. javax.inject.Named has no @Target, so without the
        // use-site target Kotlin would annotate the property instead of the field, and the
        // qualifier would be invisible to field-based reflection.
        @Inject
        @field:Named("primary")
        var primary: String? = null

        @Inject
        @field:Named("secondary")
        var secondary: String? = null

        @Inject
        var unqualified: String? = null
    }

    @Test
    fun namedBinding_resolvesByQualifier() {
        val injector =
            SimpleInjector.builder()
                .bind(String::class.java, "primary", "first")
                .bind(String::class.java, "secondary", "second")
                .bind(String::class.java, "plain")
                .build()

        val target = NamedTarget()
        injector.injectMembers(target)

        assertThat(target.primary).isEqualTo("first")
        assertThat(target.secondary).isEqualTo("second")
        assertThat(target.unqualified).isEqualTo("plain")
    }

    @Test
    fun namedBinding_doesNotCrossResolve() {
        val injector = SimpleInjector.builder().bind(String::class.java, "other", "value").build()

        val target = NamedTarget()
        injector.injectMembers(target)

        // @Named("primary") should not match @Named("other")
        assertThat(target.primary).isNull()
        assertThat(target.unqualified).isNull()
    }

    @Test
    fun register_withQualifier() {
        val injector = SimpleInjector.builder().build()

        injector.register(String::class.java, "primary", "value")

        val target = NamedTarget()
        injector.injectMembers(target)

        assertThat(target.primary).isEqualTo("value")
        assertThat(target.secondary).isNull()
    }
}
