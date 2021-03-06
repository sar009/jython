// Copyright (c)2019 Jython Developers
// Licensed to the PSF under a Contributor Agreement
package org.python.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.Test;
import org.python.util.PythonInterpreter;

/**
 * Unit test exercising the import and type system from concurrent threads. Over the years, Jython
 * has experienced multiple issues with respect to the management of types and import, in the
 * presence of multiple threads and interpreters. It seems difficult to get this right.
 * <p>
 * The initial version of this unit test was created in response to
 * <a href="https://bugs.jython.org/issue2834">issue 2834</a>.
 */
public class ConcurrentTypeTest {

    private static int RUNNERS = 30;

    static {
        // Do not need site.py for test: makes more complicated in IDE.
        Options.importSite = false;
    }

    private abstract static class ScriptRunner implements Runnable {

        final String script;
        final Thread thread;
        final PyStringMap globals = Py.newStringMap();
        /** Sub-class constructor must assign the configured interpreter. */
        protected PythonInterpreter interp;

        ScriptRunner(String script) {
            this.script = script;
            this.thread = new Thread(this);
        }

        @Override
        public void run() {
            interp.exec(script);
        }
    }

    /**
     * Test concurrency when importing the same Java class where the interpreters all share a
     * {@code PySystemState}.
     */
    @Test
    public void testSharedState() {

        // Make all the runners in advance.
        List<SharedStateRunner> runners = new ArrayList<>(RUNNERS);
        for (int i = 0; i < RUNNERS; i++) {
            runners.add(new SharedStateRunner(javaImportScript));
        }

        // Start the runners then let all of them finish (or fail).
        awaitAll(runners);

        // Check status of every thread
        for (SharedStateRunner r : runners) {
            PyObject status = r.globals.__finditem__("status");
            assertTrue("status not set to an int", status instanceof PyInteger);
            assertEquals(((PyInteger) status).asInt(), 1);
        }
    }

    /**
     * Script to import all names from a Java class for {@link #testSharedState()} and
     * {@link #testSeparateState()}.
     */
    //@formatter:off
    static final String javaImportScript = String.join("\n", new String[] {
        "from javax.swing.text.Utilities import *",
        "try:",
        "    f = getNextWord",
        "    status = 1",
        "except Exception:",
        "    status = 0"
    });
    //@formatter:on

    /**
     * Each instance of this type has its own interpreter, but they all share the same (default)
     * {@code PySystemState}
     */
    private static class SharedStateRunner extends ScriptRunner {

        SharedStateRunner(String script) {
            super(script);
            this.interp = new PythonInterpreter(globals);
        }
    }

    /**
     * Test concurrency when importing the same Java class where the interpreters all have their own
     * {@code PySystemState}.
     */
    @Test
    public void testSeparateState() {

        // Make all the runners in advance.
        List<SeparateStateRunner> runners = new ArrayList<>(RUNNERS);
        for (int i = 0; i < RUNNERS; i++) {
            runners.add(new SeparateStateRunner(javaImportScript));
        }

        // Start the runners then let all of them finish (or fail).
        awaitAll(runners);

        // Check status of every thread
        for (SeparateStateRunner r : runners) {
            PyObject status = r.globals.__finditem__("status");
            assertTrue("status not set to an int", status instanceof PyInteger);
            assertEquals(((PyInteger) status).asInt(), 1);
        }
    }

    /**
     * Each instance of this type has its own {@code PySystemState}, as well as its own interpreter.
     */
    private static class SeparateStateRunner extends ScriptRunner {

        final PySystemState sys = new PySystemState();

        SeparateStateRunner(String script) {
            super(script);
            this.interp = new PythonInterpreter(globals, sys);
        }

    }

    /**
     * Test concurrency when importing the same Java class where the interpreters all have their own
     * {@code ClassLoader}. In this variant we import * from Foo, and test the static members.
     */
    @Test
    public void testSeparateLoader() {

        // Compile the Java source and cache it in this file manager: */
        ClassCacheFileManager fileManager = getClassCacheFileManager(loadedJava, "Foo");

        // Make all the runners in advance, primed with the same script.
        List<SeparateLoaderRunner> runners = new ArrayList<>(RUNNERS);
        for (int i = 0; i < RUNNERS; i++) {
            runners.add(new SeparateLoaderRunner(loaderScript, fileManager.newClassLoader()));
        }

        // Start the runners then let all of them finish (or fail).
        awaitAll(runners);

        // Check status of every thread
        for (SeparateLoaderRunner r : runners) {
            PyObject staticConstant = r.globals.__finditem__("staticConstant");
            assertEquals(staticConstant.asInt(), 42);
            PyObject x = r.globals.__finditem__("x");
            assertEquals(x.asInt(), 42);
        }
    }

    /**
     * A class defined in Java that is compiled as part of the tests {@link #testSeparateLoader()}
     * and {@link #testSeparateLoader2()} and made available to Jython through a sp[ecific class
     * loader. See {@link ClassCacheFileManager}.
     */
    //@formatter:off
    static final String loadedJava = String.join("\n", new String[] {
        "package thin.air;",
        "public class Foo {",
        "    public static final int staticConstant = 42;",
        "    public String member = \"forty-two\";",
        "    public static int staticMethod() { return 42; }",
        "    public String method() { return member; }",
        "}"
    });
    //@formatter:on

    /**
     * Script to import all names from a Java class conjured from thin air (via class loader), usaed
     * by {@link #testSeparateLoader()}.
     */
    //@formatter:off
    static final String loaderScript = String.join("\n", new String[] {
        "from thin.air.Foo import *",
        "x = staticMethod()"
    });
    //@formatter:on

    /**
     * Test concurrency when importing the same Java class where the interpreters all have their own
     * {@code ClassLoader}. In this variant we import Foo, and instantiate one to test the instance
     * members.
     */
    @Test
    public void testSeparateLoader2() {

        // Compile the Java source and cache it in this file manager: */
        ClassCacheFileManager fileManager = getClassCacheFileManager(loadedJava, "Foo");

        // Make all the runners in advance, primed with the same script.
        List<SeparateLoaderRunner> runners = new ArrayList<>(RUNNERS);
        for (int i = 0; i < RUNNERS; i++) {
            runners.add(new SeparateLoaderRunner(loaderScript2, fileManager.newClassLoader()));
        }

        // Start the runners then let all of them finish (or fail).
        awaitAll(runners);

        // Check status of every thread
        Set<Object> classes = new HashSet<>();
        Set<PyType> types = new HashSet<>();

        for (SeparateLoaderRunner r : runners) {
            PyObject m = r.globals.__finditem__("m");
            assertEquals(m.toString(), "forty-two");
            PyObject x = r.globals.__finditem__("x");
            assertEquals(x.toString(), "forty-two");
            PyType f = (PyType) r.globals.__finditem__("Foo");
            types.add(f);
            Object c = JyAttribute.getAttr(f, JyAttribute.JAVA_PROXY_ATTR);
            classes.add(c);
        }

        // XXX At the moment, these assertions fail (see https://bugs.jython.org/issue2834).
        // assertEquals("Runners did not make a unique PyType Foo", runners.size(), types.size());
        // assertEquals("Runners did not load a unique Class Foo", runners.size(), classes.size());
    }

    /**
     * Script to import a Java class conjured from thin air (via class loader). Used by
     * {@link #testSeparateLoader2()}
     */
    //@formatter:off
    static final String loaderScript2 = String.join("\n", new String[] {
        "from thin.air import Foo",
        "f = Foo()",
        "m = f.member",
        "x = f.method()"
    });
    //@formatter:on

    /**
     * Each instance of this type has its own {@code ClassLoader}, as well as its own {@code sys}
     * module and interpreter.
     */
    private static class SeparateLoaderRunner extends ScriptRunner {

        final PySystemState sys = new PySystemState();

        SeparateLoaderRunner(String script, ClassLoader classLoader) {
            super(script);
            sys.setClassLoader(classLoader);
            this.interp = new PythonInterpreter(globals, sys);
        }
    }

    /**
     * A file manager that stores class files locally as byte arrays, for use with the Java compiler
     * tool.
     */
    private static class ClassCacheFileManager extends ForwardingJavaFileManager<JavaFileManager> {

        final Map<String, ClassFileObject> map;

        protected ClassCacheFileManager(JavaFileManager fileManager) {
            super(fileManager);
            this.map = new HashMap<>();
        }

        /**
         * A {@code JavaFileObject} where writes go to an enclosed {@code ByteArrayOutputStream},
         * intended to capture a class definition.
         */
        protected class ClassFileObject extends SimpleJavaFileObject {

            private final String className;
            private ByteArrayOutputStream stream;

            ClassFileObject(String className) {
                // JavaFileObject (superclass) requires a URI, so make up a protocol.
                super(URI.create("map:///" + className), Kind.CLASS);
                this.className = className;
            }

            /**
             * Create a stream that is cached in the map of the enclosing {@code FileManager}
             * against the className given in the constructor, and provide it to the client for
             * writing.
             */
            @Override
            public OutputStream openOutputStream() throws IOException {
                // We'll store the bytes in an array. (It's elastic, but start with 1K.)
                stream = new ByteArrayOutputStream(1024);
                map.put(className, this);
                return stream;
            }
        }

        @Override
        public JavaFileObject getJavaFileForInput(Location location, String className, Kind kind)
                throws IOException {
            ClassFileObject file;
            if (kind == Kind.CLASS && (file = map.get(className)) != null) {
                return file;
            }
            return super.getJavaFileForInput(location, className, kind);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, Kind kind,
                FileObject sibling) throws IOException {
            if (kind == Kind.CLASS) {
                return new ClassFileObject(className);
            }
            return super.getJavaFileForOutput(location, className, kind, sibling);
        }

        public ClassLoader newClassLoader() {
            return new ClassLoader() {

                @Override
                protected Class<?> findClass(String className) throws ClassNotFoundException {
                    ClassFileObject file = map.get(className);
                    if (file != null) {
                        byte b[] = file.stream.toByteArray();
                        return defineClass(className, b, 0, b.length);
                    } else {
                        throw new ClassNotFoundException();
                    }
                }
            };
        }
    }

    /**
     * Helper to set up a compiled Java class in memory, to load through the apparatus defined
     * above, for {@link #testSeparateLoader()}.
     */
    private ClassCacheFileManager getClassCacheFileManager(final String javaSource, String name) {
        // Compile Java class to import via loader.
        final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager stdFileManager = COMPILER.getStandardFileManager(null, null, null);

        // Get a String treated as a source file
        JavaFileObject sourceFile = new SimpleJavaFileObject(
                URI.create("string:///" + name + Kind.SOURCE.extension), Kind.SOURCE) {

            @Override
            public CharSequence getCharContent(boolean ignore) {
                return javaSource;
            }
        };
        Iterable<? extends JavaFileObject> compilationUnits = Collections.singletonList(sourceFile);

        // Arrange to catch the class definition file(s) in byte arrays.
        ClassCacheFileManager fileManager = new ClassCacheFileManager(stdFileManager);

        // Create a task (future) to perform the compilation
        CompilationTask task =
                COMPILER.getTask(null, fileManager, null, null, null, compilationUnits);
        assertTrue("Compilation of Java class failed", task.call());

        return fileManager;
    }

    /** Start each thread and wait for it to complete. */
    private void awaitAll(List<? extends ScriptRunner> runners) {
        // Start the runners in their threads
        for (ScriptRunner r : runners) {
            r.thread.start();
        }

        // Wait for all the runners to finish (but don't wait forever)
        boolean running = true;
        for (int attempts = 0; running && attempts < 10; attempts++) {
            running = false;
            for (ScriptRunner r : runners) {
                Thread t = r.thread;
                try {
                    t.join(100);
                } catch (InterruptedException e) {/* meh */}
                running |= t.isAlive();
            }
        }
        assertFalse("runners did not finish", running);
    }
}
