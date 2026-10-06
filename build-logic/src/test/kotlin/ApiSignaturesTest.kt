import java.io.File
import javax.tools.ToolProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ApiSignaturesTest {
    @TempDir lateinit var root: File

    private fun inspect(source: String, name: String = "Api"): String {
        val dir = File(root, "fixture-${root.listFiles()!!.size}").apply { mkdirs() }
        val file = File(dir, "$name.java").apply { writeText(source) }
        val output = File(dir, "classes").apply { mkdirs() }
        val compiler = ToolProvider.getSystemJavaCompiler()
        check(compiler.run(null, null, null, "--release", "21", "-d", output.path, file.path) == 0)
        val bin = File(System.getProperty("java.home"), "bin")
        val javap = File(bin, if (File(bin, "javap.exe").isFile) "javap.exe" else "javap")
        return ApiSignatures.inspect(javap, listOf(output))
    }

    @Test fun recordsGenericsConstantsExceptionsAndDescriptorsAreVisible() {
        val text = inspect("""
            package fixture;
            public class Api<T> {
                public static final int LIMIT = 7;
                public static final long LARGE = 1234567890123L;
                public static final double RATIO = 1.5;
                protected T value;
                public T read() throws java.io.IOException { return null; }
                public record Value(String text) {}
                public enum Mode { FIRST, SECOND }
                private static class Hidden { public void secret() {} }
                static class PackageHidden { public static class Nested {} }
            }
        """.trimIndent())
        assertThat(text).contains("public class fixture.Api<T>", "LIMIT = 7;", "LARGE = 1234567890123l;",
            "RATIO = 1.5d;", "protected T value;",
            "public T read() throws java.io.IOException;", "descriptor: ()Ljava/lang/Object;",
            "fixture.Api\$Value extends java.lang.Record", "fixture.Api\$Mode")
            .doesNotContain("Hidden", "secret", "Compiled from")
    }

    @Test fun implementationAndDeclarationOrderDoNotChangeTheBaseline() {
        val before = inspect("public class Api { public int a() { return 1; } public String b() { return null; } }")
        val after = inspect("public class Api { public String b() { return \"changed\"; } private int hidden; public int a() { return 2; } }")
        assertThat(after).isEqualTo(before)
    }

    @Test fun removalsDescriptorChangesConstantsAndNarrowerAccessChangeTheBaseline() {
        val before = inspect("public class Api { public static final int LIMIT = 7; public Number read() { return null; } }")
        for (source in listOf(
            "public class Api { public static final int LIMIT = 7; }",
            "public class Api { public static final int LIMIT = 7; public Integer read() { return null; } }",
            "public class Api { public static final int LIMIT = 8; public Number read() { return null; } }",
            "public class Api { public static final int LIMIT = 7; protected Number read() { return null; } }"
        )) assertThat(inspect(source)).isNotEqualTo(before)
    }

    @Test fun syntheticBridgeDescriptorsAreRetained() {
        val text = inspect("""
            public class Api implements java.util.function.Supplier<String> {
                public String get() { return "value"; }
            }
        """.trimIndent())
        assertThat(text).contains("descriptor: ()Ljava/lang/String;", "descriptor: ()Ljava/lang/Object;")
    }

    @Test fun nestedTypeAccessUsesInnerClassMetadataEvenWithAPublicConstructor() {
        val before = inspect("public class Api { public static class Nested { public Nested() {} } }")
        val after = inspect("public class Api { protected static class Nested { public Nested() {} } }")
        assertThat(before).contains("public static class Api\$Nested")
        assertThat(after).contains("protected static class Api\$Nested").isNotEqualTo(before)
    }

    @Test fun dollarInATopLevelNameDoesNotMakeItANestedType() {
        assertThat(inspect("public class Api\$Dollar {}", "Api\$Dollar")).contains("public class Api\$Dollar")
    }

    @Test fun malformedOutputFailsInsteadOfApprovingAnIncompleteMember() {
        assertThatThrownBy { ApiSignatures.canonicalize("public class Api {\n  public void read();\n}") }
            .hasMessageContaining("Missing JVM descriptor")
    }

    @Test fun modulesWithOnlyImplementationTypesHaveAnExplicitEmptyBaseline() {
        assertThat(inspect("package fixture.internal; public class Api {}"))
            .contains("# No exported declarations.")
    }

    @Test fun unreadableClassMetadataFailsClosed() {
        val broken = File(root, "Broken.class").apply { writeBytes(byteArrayOf(0, 0, 0, 0)) }
        assertThatThrownBy { ApiClassAccess.read(broken) }.hasMessageContaining("Not a class file")
    }

    @Test fun diagnosticsRetainTheOwnerWhenAnotherClassHasTheSameMember() {
        val member = "  public void run();\n    descriptor: ()V\n"
        val before = "public class First {\n${member}}\n\npublic class Second {\n${member}}\n"
        val after = "public class First {\n}\n\npublic class Second {\n${member}}\n"
        assertThat(ApiSignatures.differences(before, after)).contains("public class First", "-   public void run();")
    }
}
