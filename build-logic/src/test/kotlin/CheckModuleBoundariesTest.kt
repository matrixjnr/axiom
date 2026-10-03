import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test

class CheckModuleBoundariesTest {
    private fun verify(module: String, vararg dependencies: String) =
        CheckModuleBoundaries.verify(module, dependencies.toList())

    @Test
    fun allowedProjectDependencyPasses() {
        assertThatCode { verify("axiom-server", "project|axiom-core|api") }.doesNotThrowAnyException()
    }

    @Test
    fun moduleMissingFromTheMapFailsWithAClearMessage() {
        assertThatThrownBy { verify("axiom-new") }
            .isInstanceOf(GradleException::class.java)
            .hasMessageContaining("'axiom-new' is not listed in CheckModuleBoundaries")
    }

    @Test
    fun forbiddenProjectDependencyFails() {
        assertThatThrownBy { verify("axiom-core", "project|axiom-server|implementation") }
            .hasMessage("Forbidden module dependency: axiom-core -> axiom-server")
    }

    @Test
    fun unlistedProjectDependencyFails() {
        assertThatThrownBy { verify("axiom-core", "project|mystery|implementation") }
            .hasMessage("Dependency on unlisted module: axiom-core -> mystery")
    }

    @Test
    fun testScopeOnlyDependencyIsAllowedInTestConfigurationsOnly() {
        assertThatCode { verify("axiom-security", "project|axiom-test|testImplementation") }
            .doesNotThrowAnyException()
        assertThatThrownBy { verify("axiom-security", "project|axiom-test|implementation") }
            .hasMessage("Forbidden module dependency: axiom-security -> axiom-test")
    }

    @Test
    fun noModuleMayDependOnATestOnlyModule() {
        assertThat(ModuleBoundaries.testOnlyModules).contains("integration-tests")
        assertThatThrownBy { verify("axiom-core", "project|integration-tests|testImplementation") }
            .hasMessage("No module may depend on test-only module: axiom-core -> integration-tests")
    }

    @Test
    fun testOnlyModuleMayDeclareOnlyTestDependencies() {
        assertThatCode { verify("integration-tests", "project|axiom-test|testImplementation") }
            .doesNotThrowAnyException()
        assertThatThrownBy { verify("integration-tests", "project|axiom-core|implementation") }
            .hasMessageContaining("Test-only module 'integration-tests' may declare only test dependencies")
    }

    @Test
    fun externalProductionDependenciesAreRejectedOutsideTheirAdapter() {
        assertThatThrownBy { verify("axiom-core", "external|com.example:lib|implementation") }
            .hasMessage("Core must remain free of external production dependencies: com.example:lib")
        assertThatThrownBy { verify("axiom-server", "external|io.netty:netty-handler|implementation") }
            .hasMessage("External production dependency outside its adapter module: axiom-server -> io.netty:netty-handler")
    }

    @Test
    fun externalDependenciesAreConfinedToTheirAdapterModule() {
        assertThatCode { verify("axiom-http", "external|io.netty:netty-handler|implementation") }
            .doesNotThrowAnyException()
        assertThatCode { verify("axiom-json", "external|com.fasterxml.jackson.core:jackson-databind|implementation") }
            .doesNotThrowAnyException()
        assertThatThrownBy { verify("axiom-json", "external|io.netty:netty-handler|implementation") }
            .hasMessageContaining("outside its adapter module")
    }

    @Test
    fun externalTestDependenciesAreAllowedOutsideCore() {
        assertThatCode { verify("axiom-server", "external|org.assertj:assertj-core|testImplementation") }
            .doesNotThrowAnyException()
    }

    @Test
    fun externalApiDependencyIsRejectedEvenInItsAdapterModule() {
        assertThatThrownBy { verify("axiom-http", "external|io.netty:netty-handler|api") }
            .hasMessage("External public API dependency needs an architecture decision: axiom-http -> io.netty:netty-handler")
    }
}
