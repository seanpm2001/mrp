/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2006
 */
package org.mmtk.vm;

import org.mmtk.vm.SynchronizedCounter;

import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;

/**
 * This class is responsible for all VM-specific functionality required
 * by MMTk.<p>
 * 
 * The class has two major elements.  First it defines VM-specific
 * constants which are used throughout MMTk.  Secondly in declares
 * singleton instances of each of the abstract classes in this
 * package.<p>
 * 
 * Both the constants and the singleton instances are initialized to
 * VM-specific values at build time using reflection.  The system
 * property <code>mmtk.hostjvm</code> is interrogated at build time
 * to establish concrete instantations of the abstract classes in 
 * this package.  By convention, <code>mmtk.hostjvm</code> will 
 * identify a VM-provided package which includes concrete instances
 * of each of the abstract classes, with each concrete class having
 * the same base class name (but different package name) as the abstract
 * classes defined here.  The class initializer for this class then
 * uses the system property <code>mmtk.hostjvm</code> to load the
 * VM-specific concrete classes and initialize the constants and
 * singletons defined here.
 * 
 * $Id: MutatorContext.java 10750 2006-09-05 05:10:30 +0000 (Tue, 05 Sep 2006) steveb-oss $
 * 
 * @author Steve Blackburn
 * @version $Revision: 10750 $
 * @date $Date: 2006-09-05 05:10:30 +0000 (Tue, 05 Sep 2006) $
 */

public final class VM {
  
  /*
   * VM-specific constant values
   */
  /** <code>true</code> if the references are implemented as heap objects */
  public static final boolean REFERENCES_ARE_OBJECTS;
  /** <code>true</code> if assertions should be verified */
  public static final boolean VERIFY_ASSERTIONS;
  /** The lowest address in virtual memory known to MMTk */
  public static final Address HEAP_START;
  /** The highest address in virtual memory known to MMTk */
  public static final Address HEAP_END;
  /** The lowest address in the contigiously available memory available to MMTk */
  public static final Address AVAILABLE_START;
  /** The highest address in the contigiously available memory available to MMTk */
  public static final Address AVAILABLE_END;
  /** The log base two of the size of an address */
  public static final byte LOG_BYTES_IN_ADDRESS;
  /** The log base two of the size of a word */
  public static final byte LOG_BYTES_IN_WORD;
  /** The log base two of the size of an OS page */
  public static final byte LOG_BYTES_IN_PAGE;
  /** The log base two of the minimum allocation alignment */
  public static final byte LOG_MIN_ALIGNMENT;
  /** The log base two of (MAX_ALIGNMENT/MIN_ALIGNMENT) */
  public static final byte MAX_ALIGNMENT_SHIFT;
  /** The maximum number of bytes of padding to prepend to an object */
  public static final int MAX_BYTES_PADDING;
  /** The value to store in alignment holes */
  public static final int ALIGNMENT_VALUE;
  /** The offset from an array reference to element zero */
  public static final Offset ARRAY_BASE_OFFSET;

  /*
   * VM-specific functionality captured in a series of singleton classs
   */
  public static final ObjectModel objectModel;
  public static final ActivePlan activePlan;
  public static final Assert assertions;
  public static final Barriers barriers;
  public static final Collection collection;
  public static final Memory memory;
  public static final Options options;
  public static final ReferenceGlue referenceTypes;
  public static final Scanning scanning;
  public static final Statistics statistics;
  public static final Strings strings;
  public static final TraceInterface traceInterface;
  
  /* Class instances to be used by factory methods */
  private static final Class lockClass;
  private static final Class counterClass;
  
  /*
   * The remainder is does the static initialization of the
   * above, reflectively binding to the appropriate host jvm
   * classes.
   */
  private static String vmPackage;
  private static String vmGCSpyPackage;
 
  static {
    vmPackage = System.getProperty("mmtk.hostjvm");
    vmGCSpyPackage = System.getProperty("mmtk.hostjvm") + ".gcspy";
    ObjectModel xom = null;
    ActivePlan xap = null;
    Assert xas = null;
    Barriers xba = null;
    Collection xco = null;
    Memory xme = null;
    Options xop = null;
    ReferenceGlue xrg = null;
    Scanning xsc = null;
    Statistics xst = null;
    Strings xsr = null;
    TraceInterface xtr = null;
    Class xlc = null;
    Class xcc = null;
    try {
      xom = (ObjectModel) Class.forName(vmPackage+".ObjectModel").newInstance();
      xap = (ActivePlan) Class.forName(vmPackage+".ActivePlan").newInstance();
      xas = (Assert) Class.forName(vmPackage+".Assert").newInstance();
      xba = (Barriers) Class.forName(vmPackage+".Barriers").newInstance();
      xco = (Collection) Class.forName(vmPackage+".Collection").newInstance();
      xme = (Memory) Class.forName(vmPackage+".Memory").newInstance();
      xop = (Options) Class.forName(vmPackage+".Options").newInstance();
      xrg = (ReferenceGlue) Class.forName(vmPackage+".ReferenceGlue").newInstance();
      xsc = (Scanning) Class.forName(vmPackage+".Scanning").newInstance();
      xst = (Statistics) Class.forName(vmPackage+".Statistics").newInstance();
      xsr = (Strings) Class.forName(vmPackage+".Strings").newInstance();
      xtr = (TraceInterface) Class.forName(vmPackage+".TraceInterface").newInstance();
      xlc = Class.forName(vmPackage+".Lock");
      xcc = Class.forName(vmPackage+".SynchronizedCounter");
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(-1);     // we must *not* go on if the above has failed
    }
    objectModel = xom;
    activePlan = xap;
    assertions = xas;
    barriers = xba;
    collection = xco;
    memory = xme;
    options = xop;
    referenceTypes = xrg;
    scanning = xsc;
    statistics = xst;
    strings = xsr;
    traceInterface = xtr;
    lockClass = xlc;
    counterClass = xcc;
    REFERENCES_ARE_OBJECTS = ReferenceGlue.referencesAreObjectsTrapdoor(referenceTypes);
    VERIFY_ASSERTIONS = Assert.verifyAssertionsTrapdoor(assertions);
    HEAP_START = Memory.heapStartTrapdoor(memory);
    HEAP_END = Memory.heapEndTrapdoor(memory);
    AVAILABLE_START = Memory.availableStartTrapdoor(memory);
    AVAILABLE_END = Memory.availableEndTrapdoor(memory);
    LOG_BYTES_IN_ADDRESS = Memory.logBytesInAddressTrapdoor(memory);
    LOG_BYTES_IN_WORD = Memory.logBytesInWordTrapdoor(memory);
    LOG_BYTES_IN_PAGE = Memory.logBytesInPageTrapdoor(memory);
    LOG_MIN_ALIGNMENT = Memory.logMinAlignmentTrapdoor(memory);
    MAX_ALIGNMENT_SHIFT = Memory.maxAlignmentShiftTrapdoor(memory);
    MAX_BYTES_PADDING = Memory.maxBytesPaddingTrapdoor(memory);
    ALIGNMENT_VALUE = Memory.alignmentValueTrapdoor(memory);
    ARRAY_BASE_OFFSET = ObjectModel.arrayBaseOffsetTrapdoor(objectModel);
 }
  /**
   * Create a new Lock instance using the appropriate VM-specific
   * concrete Lock sub-class.
   * 
   * @param name The string to be associated with this lock instance
   * @return A concrete VM-specific Lock instance.
   */
  public static Lock newLock(String name) {
    try {
      return (Lock) lockClass.newInstance();
    } catch (Exception e) {
      assertions.fail("Failed to allocate lock!");
      return null; // never get here
    }
  }
  
  /**
   * Create a new SynchronizedCounter instance using the appropriate
   * VM-specific concrete SynchronizedCounter sub-class.
   * 
   * @return A concrete VM-specific SynchronizedCounter instance.
   */
  public static SynchronizedCounter newSynchronizedCounter() {
    try {
      return (SynchronizedCounter) counterClass.newInstance();
    } catch (Exception e) {
      assertions.fail("Failed to clone counter!");
      return null; // never get here
    }
  }

}
