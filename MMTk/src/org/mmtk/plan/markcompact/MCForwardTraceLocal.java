/*
 * This file is part of MMTk (http://jikesrvm.sourceforge.net).
 * MMTk is distributed under the Common Public License (CPL).
 * A copy of the license is included in the distribution, and is also
 * available at http://www.opensource.org/licenses/cpl1.0.php
 *
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2005
 */
package org.mmtk.plan.markcompact;

import org.mmtk.plan.TraceLocal;
import org.mmtk.plan.Trace;
import org.mmtk.policy.Space;
import org.mmtk.vm.VM;

import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.*;

/**
 * This abstract class implments the thread-local functionality for a transitive
 * closure over a mark-compact space during the forwarding phase.
 */
@Uninterruptible public final class MCForwardTraceLocal extends TraceLocal {
  /**
   * Constructor
   */
  public MCForwardTraceLocal(Trace trace) {
    super(trace);
  }

  /****************************************************************************
   * 
   * Externally visible Object processing and tracing
   */

  /**
   * Is the specified object live?
   * 
   * @param object The object.
   * @return True if the object is live.
   */
  public boolean isLive(ObjectReference object) {
    if (object.isNull()) return false;
    if (Space.isInSpace(MC.MARK_COMPACT, object)) {
      return MC.mcSpace.isLive(object);
    }
    return super.isLive(object);
  }

  /**
   * This method is the core method during the trace of the object graph.
   * The role of this method is to:
   * 
   * 1. Ensure the traced object is not collected.
   * 2. If this is the first visit to the object enqueue it to be scanned.
   * 3. Return the forwarded reference to the object.
   * 
   * In this instance, we refer objects in the mark-sweep space to the
   * msSpace for tracing, and defer to the superclass for all others.
   * 
   * @param object The object to be traced.
   * @return The new reference to the same object instance.
   */
  @Inline
  public ObjectReference traceObject(ObjectReference object) { 
    if (object.isNull()) return object;
    if (Space.isInSpace(MC.MARK_COMPACT, object))
      return MC.mcSpace.traceForwardObject(this, object);
    return super.traceObject(object);
  }

  /**
   * Will this object move from this point on, during the current trace ?
   * 
   * @param object The object to query.
   * @return True if the object will not move.
   */
  public boolean willNotMove(ObjectReference object) {
    return !Space.isInSpace(MC.MARK_COMPACT, object);
  }

  /**
   * Ensure that this object will not move for the rest of the GC.
   * 
   * @param object The object that must not move
   * @return The new object, guaranteed stable for the rest of the GC.
   */
  @Inline
  public ObjectReference precopyObject(ObjectReference object) { 
    if (VM.VERIFY_ASSERTIONS) {
      // All precopying must occur during the initial trace.
      VM.assertions._assert(!Space.isInSpace(MC.MARK_COMPACT, object));
    }
    return super.precopyObject(object);
  }

}
