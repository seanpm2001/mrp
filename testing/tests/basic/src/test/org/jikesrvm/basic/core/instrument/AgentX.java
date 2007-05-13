/*
 * This file is part of Jikes RVM (http://jikesrvm.sourceforge.net).
 * The Jikes RVM project is distributed under the Common Public License (CPL).
 * A copy of the license is included in the distribution, and is also
 * available at http://www.opensource.org/licenses/cpl1.0.php
 *
 * (C) Copyright Elias Naur 2006
 */
package test.org.jikesrvm.basic.core.instrument;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public class AgentX implements ClassFileTransformer {
  public static void premain(final String args, final Instrumentation instrumentation) {
    System.out.println("Running premain with args: " + args);
    System.out.println("Adding class transformer");
    instrumentation.addTransformer(new AgentX());
    final Object[] array = new Object[10];
    final Object object = new Object();
    long arraySize = instrumentation.getObjectSize(array);
    long objectSize = instrumentation.getObjectSize(object);
    // Conservatively assume sizeof(Object) > 0 and sizeof(array) >= array.length
    System.out.println("Array size ok: " + (arraySize >= array.length));
    System.out.println("Object size ok: " + (objectSize > 0));
    System.out.println("AgentX in initiated classes? " +
        findClass(instrumentation.getInitiatedClasses(AgentX.class.getClassLoader()), AgentX.class));
    System.out.println("AgentX in all classes? " +
        findClass(instrumentation.getAllLoadedClasses(), AgentX.class));
  }

  private static boolean findClass(final Class[] classes, final Class klass) {
    // See if we're in the list of initiated classes
    for (Class c : classes) {
      if (c == klass) return true;
    }
    return false;
  }

  public byte[] transform(ClassLoader loader, String className, Class classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
    if (true || className.equals("test.org.jikesrvm.basic.core.instrument.TestAgent")) {
      System.out.println("Transforming class: " + className);
    }
    // I'm too lazy to actually change the class, so we'll just pretend we did by returning non-null
    return classfileBuffer;
  }
}
