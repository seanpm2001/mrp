/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Common Public License (CPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/cpl1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.adaptive;

import org.jikesrvm.adaptive.controller.VM_Controller;
import org.jikesrvm.adaptive.controller.VM_ControllerInputEvent;
import org.jikesrvm.adaptive.controller.VM_ControllerMemory;
import org.jikesrvm.adaptive.controller.VM_ControllerPlan;
import org.jikesrvm.adaptive.util.VM_AOSLogging;
import org.jikesrvm.classloader.VM_NormalMethod;
import org.jikesrvm.compilers.common.VM_CompiledMethod;
import org.jikesrvm.compilers.common.VM_CompiledMethods;
import org.jikesrvm.compilers.common.VM_RuntimeCompiler;
import org.jikesrvm.compilers.opt.CompilationPlan;
import org.jikesrvm.compilers.opt.OptimizationPlanElement;
import org.jikesrvm.compilers.opt.Options;
import org.jikesrvm.scheduler.VM_Thread;
import org.vmmagic.unboxed.Offset;

/**
 * Event generated by a thread aware of on-stack-replacement request.
 * The event is feed to the controller with suspended thread, and hot
 * method id. Since it does not need to go through analytic model, it does
 * not extend the VM_HotMethodEvent.
 */

public final class OSR_OnStackReplacementEvent implements VM_ControllerInputEvent {

  /** the suspended thread. */
  public VM_Thread suspendedThread;

  /** remember where it comes from */
  public int whereFrom;

  /** the compiled method id */
  public int CMID;

  /** the threadSwithFrom fp offset */
  public Offset tsFromFPoff;

  /** the osr method's fp offset */
  public Offset ypTakenFPoff;

  /**
   * This function will generate a controller plan and
   * inserted in the recompilation queue.
   */
  public void process() {

    VM_CompiledMethod compiledMethod = VM_CompiledMethods.getCompiledMethod(CMID);

    VM_NormalMethod todoMethod = (VM_NormalMethod) compiledMethod.getMethod();

    double priority;
    Options options;
    OptimizationPlanElement[] optimizationPlan;

    VM_ControllerPlan oldPlan = VM_ControllerMemory.findLatestPlan(todoMethod);

    if (oldPlan != null) {
      CompilationPlan oldCompPlan = oldPlan.getCompPlan();
      priority = oldPlan.getPriority();
      options = oldCompPlan.options;
      optimizationPlan = oldCompPlan.optimizationPlan;
    } else {
      priority = 5.0;
      options = (Options) VM_RuntimeCompiler.options;
      optimizationPlan = (OptimizationPlanElement[]) VM_RuntimeCompiler.optimizationPlan;
    }

    CompilationPlan compPlan = new CompilationPlan(todoMethod, optimizationPlan, null, options);

    OSR_OnStackReplacementPlan plan =
        new OSR_OnStackReplacementPlan(this.suspendedThread,
                                       compPlan,
                                       this.CMID,
                                       this.whereFrom,
                                       this.tsFromFPoff,
                                       this.ypTakenFPoff,
                                       priority);

    VM_Controller.compilationQueue.insert(priority, plan);

    VM_AOSLogging.logOsrEvent("OSR inserts compilation plan successfully!");

    // do not hold the reference anymore.
    suspendedThread = null;
    CMID = 0;
  }
}
