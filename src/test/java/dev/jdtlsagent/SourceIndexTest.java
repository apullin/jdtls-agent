package dev.jdtlsagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SourceIndexTest {
    @TempDir
    Path project;

    @Test
    void resolvesFullyQualifiedClassAndMethodSignatures() throws Exception {
        write("java/src/toy/Foo.java", """
                package toy;

                public final class Foo {
                    public static void bar() {
                    }

                    public static void bar(String name, int count) {
                    }

                    public int value;
                }
                """);

        SourceIndex index = SourceIndex.build(project, "java/src", "java/test");

        SourceIndex.ResolveResult classResult = index.resolve("toy.Foo", true);
        assertTrue(classResult.ok());
        assertEquals("class", classResult.symbol().kind());

        SourceIndex.ResolveResult noArg = index.resolve("toy.Foo.bar()", true);
        assertTrue(noArg.ok());
        assertEquals(0, noArg.symbol().parameterTypes().size());

        SourceIndex.ResolveResult overloaded = index.resolve("toy.Foo.bar(String,int)", true);
        assertTrue(overloaded.ok());
        assertEquals(2, overloaded.symbol().parameterTypes().size());

        SourceIndex.ResolveResult ambiguous = index.resolve("toy.Foo.bar", true);
        assertFalse(ambiguous.ok());
        assertEquals("ambiguous-symbol", ambiguous.error());
    }

    @Test
    void filtersTestSourcesWhenRequested() throws Exception {
        write("java/src/toy/Foo.java", """
                package toy;
                public final class Foo {
                    public static void target() {}
                }
                """);
        write("java/test/toy/FooSelfTest.java", """
                package toy;
                public final class FooSelfTest {
                    public static void target() {}
                }
                """);

        SourceIndex index = SourceIndex.build(project, "java/src", "java/test");

        assertEquals(2, index.search("target", true, 10).size());
        assertEquals(1, index.search("target", false, 10).size());
        assertTrue(index.resolve("FooSelfTest.target", true).ok());
        assertFalse(index.resolve("FooSelfTest.target", false).ok());
    }

    @Test
    void indexesApiSurfaceAndModifiers() throws Exception {
        write("java/src/toy/Foo.java", """
                package toy;
                final class Foo {
                    static void packageStatic() {}
                    private void hidden() {}
                    public void visible() {}
                }
                """);

        SourceIndex index = SourceIndex.build(project, "java/src", "java/test");

        assertEquals(2, index.apiSurface("toy.Foo", true, 10).size());
        SourceIndex.ResolveResult visible = index.resolve("toy.Foo.visible", true);
        assertTrue(visible.ok());
        assertTrue(visible.symbol().modifiers().contains("public"));
    }

    @Test
    void classifiesFieldWriteReferencesByAstContext() throws Exception {
        write("java/src/toy/Foo.java", """
                package toy;
                final class Foo {
                    int value;
                    void touch(Foo other) {
                        value = 1;
                        this.value += 2;
                        other.value++;
                        int copy = other.value;
                    }
                }
                """);

        SourceIndex index = SourceIndex.build(project, "java/src", "java/test");
        Path file = project.resolve("java/src/toy/Foo.java");

        assertTrue(index.isWriteReference(file, index.offset(file, 5, 9)));
        assertTrue(index.isWriteReference(file, index.offset(file, 6, 14)));
        assertTrue(index.isWriteReference(file, index.offset(file, 7, 19)));
        assertFalse(index.isWriteReference(file, index.offset(file, 8, 26)));
    }


    private void write(String relative, String content) throws Exception {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
