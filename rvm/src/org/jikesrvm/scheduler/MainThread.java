/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.scheduler;

import static org.jikesrvm.runtime.ExitStatus.EXIT_STATUS_BOGUS_COMMAND_LINE_ARG;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.Atom;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMClassLoader;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.classloader.TypeReference;
import org.jikesrvm.runtime.Callbacks;
import org.jikesrvm.runtime.CommandLineArgs;
import org.jikesrvm.runtime.Reflection;
import org.vmmagic.pragma.Entrypoint;

/**
 * Thread in which user's "main" program runs.
 */
public final class MainThread extends Thread {
  private final String[] args;
  private final String[] agents;
  private RVMMethod mainMethod;
  protected boolean launched = false;

  private static final boolean dbg = false;

  /**
   * Create "main" thread.
   * @param args {@code args[0]}: name of class containing "main" method;
   *  {@code args[1..N]}: parameters to pass to "main" method
   * @param threadGroup thread group for the main thread
   */
  public MainThread(String[] args, ThreadGroup threadGroup) {
    super(threadGroup, "MainThread");
    setDaemon(false); // NB otherwise we inherit the boot threads daemon status
    this.agents = CommandLineArgs.getJavaAgentArgs();
    this.args = args;
    if (dbg) {
      VM.sysWriteln("MainThread(args.length == ", args.length, "): constructor done");
    }
  }

  private void runAgents(ClassLoader cl) {
    Instrumentation instrumenter = null;
    if (agents.length > 0) {
      if (VM.verboseBoot >= 1) VM.sysWriteln("Booting instrumentation for agents");
      try {
        instrumenter = setupInstrumentation();
      } catch (Exception e) {
        if (VM.verboseBoot >= 1) VM.sysWriteln("Booting instrumentation for agents FAILED");
      }

      for (String agent : agents) {
        /*
         * Parse agent string according to the form
         * given in the java.lang.instrumentation package
         * documentation:
         * jarpath[=options]
         *
         * (The -javaagent: part of the agent options has
         *  already been stripped)
         */
        int equalsIndex = agent.indexOf('=');
        String agentJar;
        String agentOptions;
        if (equalsIndex != -1) {
          agentJar = agent.substring(0, equalsIndex);
          agentOptions = agent.substring(equalsIndex + 1);
        } else {
          agentJar = agent;
          agentOptions = "";
        }
        runAgent(instrumenter, cl, agentJar, agentOptions);
      }
    }
  }

  private static Instrumentation setupInstrumentation() throws Exception {
    Instrumentation instrumenter = null;
    if (VM.BuildForGnuClasspath) {
        instrumenter = (Instrumentation)Class.forName("gnu.java.lang.JikesRVMSupport")
          .getMethod("createInstrumentation").invoke(null);
        java.lang.JikesRVMSupport.initializeInstrumentation(instrumenter);
    } else if (VM.BuildForOpenJDK) {
      // FIXME OPENJDK/ICEDTEA initializeInstrumentation isn't implemented yet.
      // OpenJDK 6 doesn't seem to provide any suitable hooks for implementation of instrumentation.
      // The instrumentation in OpenJDK is done via native code which doesn't seem to be called automatically.
      // That means we have to (re-)implement instrumentation ourselves and do it during classloading.
      // Some relevant code in OpenJDK 6:
      // JPLISAgent.c (openjdk/jdk/src/share/instrument)
      // JPLISAgent.h (openjdk/jdk/src/share/instrument)
      // sun.instrument.InstrumentationImpl (openjdk/jdk/src/share/classes/sun/instrument)
      Class<?> instrumentationClass = Class.forName("sun.instrument.InstrumentationImpl");
      Class[] constructorParameters = {long.class, boolean.class, boolean.class};
      Constructor<?> constructor = instrumentationClass.getDeclaredConstructor(constructorParameters);
      Object[] parameter = {Long.valueOf(0L), Boolean.FALSE, Boolean.FALSE};
      instrumenter = (Instrumentation)constructor.newInstance(parameter);
      java.lang.JikesRVMSupport.initializeInstrumentation(instrumenter);

    }
    return instrumenter;
  }

  private static void runAgent(Instrumentation instrumenter, ClassLoader cl, String agentJar, String agentOptions) {
    Manifest mf = null;
    try {
      JarFile jf = new JarFile(agentJar);
      mf = jf.getManifest();
    } catch (Exception e) {
      VM.sysWriteln("vm: IO Exception opening JAR file ", agentJar, ": ", e.getMessage());
      VM.sysExit(EXIT_STATUS_BOGUS_COMMAND_LINE_ARG);
    }
    if (mf == null) {
      VM.sysWriteln("The jar file is missing the manifest: ", agentJar);
      VM.sysExit(EXIT_STATUS_BOGUS_COMMAND_LINE_ARG);
    }
    String agentClassName = mf.getMainAttributes().getValue("Premain-Class");
    if (agentClassName == null) {
      VM.sysWriteln("The jar file is missing the Premain-Class manifest entry for the agent class: ", agentJar);
      VM.sysExit(EXIT_STATUS_BOGUS_COMMAND_LINE_ARG);
    }
    //TODO: By this stage all agent jars and classes they reference via their manifest
    try {
      Class<?> agentClass = cl.loadClass(agentClassName);
      Method agentPremainMethod = agentClass.getMethod("premain", new Class<?>[]{String.class, Instrumentation.class});
      agentPremainMethod.invoke(null, new Object[]{agentOptions, instrumenter});
    } catch (InvocationTargetException e) {
      // According to the spec, exceptions from premain() can be ignored
    } catch (Throwable e) {
      VM.sysWriteln("Failed to run the agent's premain: " + e.getMessage());
      e.printStackTrace();
      System.exit(0);
    }
  }

  RVMMethod getMainMethod() {
    return mainMethod;
  }

  /**
   * Run "main" thread.
   * <p>
   * This code could be made a little shorter by relying on Reflection
   * to do the classloading and compilation.  We intentionally do it here
   * to give us a chance to provide error messages that are specific to
   * not being able to find the main class the user wants to run.
   * This may be a little silly, since it results in code duplication
   * just to provide debug messages in a place where very little is actually
   * likely to go wrong, but there you have it....
   */
  @Override
  @Entrypoint
  public void run() {
    launched = true;

    if (dbg) VM.sysWriteln("MainThread.run() starting ");

    // Set up application class loader
    ClassLoader cl = RVMClassLoader.getApplicationClassLoader();
    setContextClassLoader(cl);

    runAgents(cl);

    if (dbg) VM.sysWrite("[MainThread.run() loading class to run... ");
    // find method to run
    // load class specified by args[0]
    RVMClass cls = null;
    try {
      Atom mainAtom = Atom.findOrCreateUnicodeAtom(args[0]);
      TypeReference mainClass = TypeReference.findOrCreate(cl, mainAtom.descriptorFromClassName());
      cls = mainClass.resolve().asClass();
      cls.prepareForFirstUse();
    } catch (NoClassDefFoundError e) {
      if (dbg) VM.sysWrite("failed.]");
      // no such class
      VM.sysWriteln(e.toString());
      return;
    }
    if (dbg) VM.sysWriteln("loaded.]");

    // find "main" method
    //
    mainMethod = cls.findMainMethod();
    if (mainMethod == null) {
      // no such method
      VM.sysWriteln(cls + " doesn't have a \"public static void main(String[])\" method to execute");
      return;
    }

    if (dbg) VM.sysWrite("[MainThread.run() making arg list... ");
    // create "main" argument list
    //
    String[] mainArgs = new String[args.length - 1];
    for (int i = 0, n = mainArgs.length; i < n; ++i) {
      mainArgs[i] = args[i + 1];
    }
    if (dbg) VM.sysWriteln("made.]");

    if (dbg) VM.sysWrite("[MainThread.run() compiling main(String[])... ");
    mainMethod.compile();
    if (dbg) VM.sysWriteln("compiled.]");

    // Notify other clients that the startup is complete.
    //
    Callbacks.notifyStartup();

    if (dbg) VM.sysWriteln("[MainThread.run() invoking \"main\" method... ");
    // invoke "main" method with argument list
    Reflection.invoke(mainMethod, null, null, new Object[]{mainArgs}, true);
    if (dbg) VM.sysWriteln("  MainThread.run(): \"main\" method completed.]");
  }
}
