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
package org.jikesrvm.ia32;

import org.jikesrvm.architecture.AbstractRegisters;
import org.jikesrvm.architecture.SizeConstants;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.VM;
import org.vmmagic.pragma.NonMoving;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.Offset;
import static org.jikesrvm.ia32.RegisterConstants.ESP;

/**
 * The machine state comprising a thread's execution context, used both for
 * thread context switching and for software/hardware exception
 * reporting/delivery.
 */
@Uninterruptible
@NonMoving
public final class Registers extends AbstractRegisters {
  /** Frame pointer */
  private Address fp;

  public Registers() { }

  @Override
  public final void clear() {
    fp=Address.zero();
    super.clear();
  }

  /**
   * Return framepointer for the deepest stackframe
   */
  public final Address getInnermostFramePointer() {
    return fp;
  }

  /**
   * Return next instruction address for the deepest stackframe
   */
  public final Address getInnermostInstructionAddress() {
    return ip;
  }

  /**
   * update the machine state as if the stackframe were unwound.
   */
  public final void unwindStackFrame() {
    ip = Magic.getReturnAddress(fp);
    fp = Magic.getCallerFramePointer(fp);
  }

  /**
   * set ip & fp. used to control the stack frame at which a scan of
   * the stack during GC will start, for ex., the top java frame for
   * a thread that is blocked in native code during GC.
   */
  public final void setInnermost(Address newip, Address newfp) {
    ip = newip;
    fp = newfp;
  }

  /**
   * set ip and fp values to those of the caller. used just prior to entering
   * sigwait to set fp & ip so that GC will scan the threads stack
   * starting at the frame of the method that called sigwait.
   */
  public final void setInnermost() {
    Address current_fp = Magic.getFramePointer();
    ip = Magic.getReturnAddress(current_fp);
    fp = Magic.getCallerFramePointer(current_fp);
  }

  /**
   * The following method initializes a thread stack as if
   * "startoff" method had been called by an empty baseline-compiled
   *  "sentinel" frame with one local variable
   *
   * @param contextRegisters The context registers for this thread
   * @param ip The instruction pointer for the "startoff" method
   * @param sp The base of the stack
   */
  @Uninterruptible
  public final void initializeStack(Address ip, Address sp) {
    Address fp;
    sp = sp.minus(StackframeLayoutConstants.STACKFRAME_HEADER_SIZE);                   // last word of header
    fp = sp.minus(SizeConstants.BYTES_IN_ADDRESS).minus(StackframeLayoutConstants.STACKFRAME_BODY_OFFSET);
    Magic.setCallerFramePointer(fp, StackframeLayoutConstants.STACKFRAME_SENTINEL_FP);
    Magic.setCompiledMethodID(fp, StackframeLayoutConstants.INVISIBLE_METHOD_ID);

    sp = sp.minus(SizeConstants.BYTES_IN_ADDRESS);                                 // allow for one local
    getGPRs().set(ESP.value(), sp.toWord());
    this.fp = fp;
    this.ip = ip;
  }

  /**
   * A thread's stack has been moved or resized.
   * Adjust the ESP register to reflect new position.
   *
   * @param registers The registers for this thread
   * @param delta The displacement to be applied
   * @param traceAdjustments Log all adjustments to stderr if true
   */
  @Uninterruptible
  @Override
  public final void adjustESP( Offset delta, boolean traceAdjustments) {
    Word old = getGPRs().get(ESP.value());
    getGPRs().set(ESP.value(), old.plus(delta));
    if (traceAdjustments) {
      VM.sysWrite(" esp =");
      VM.sysWrite(getGPRs().get(ESP.value()));
    }
  }

  @Override
  public final void dump() {
    super.dump();
    VM.sysWriteln("fp = ",fp);
  }
}
