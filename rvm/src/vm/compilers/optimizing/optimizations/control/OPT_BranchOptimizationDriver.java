/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$

import instructionFormats.*;

/**
 * IR level independent driver for 
 * simple peephole optimizations of branches.
 * 
 * @author Stephen Fink
 * @author Dave Grove
 * @author Mauricio Serrano
 */
public abstract class OPT_BranchOptimizationDriver 
  extends OPT_CompilerPhase
  implements OPT_Operators {

  /**
   * Optimization level.  TODO: This is kludge, right?  I think this
   * can go when GCP is mainstreamed.  Martin?
   * I think we might still need this (maybe could be fixed by tweaking
   * composite phases??) --dave
   */
  private int _level;

  /**
   * Slightly restrict the branch optimizations to ensure that
   * conditional branches are not duplicated.  This is necessary only
   * for the very first branch optimizations performed after BC2IR.
   * If conditional branches are duplicated, it creates multiple
   * branches with the same bytecode offset which interferes with the 
   * mapping used for edge count profiling.
   */
  protected boolean _restrictCondBranchOpts = false;

  protected OPT_BranchOptimizationDriver() {}

  /** 
   * @param level the minimum optimization level at which the branch 
   * optimizations should be performed.
   */
  OPT_BranchOptimizationDriver(int level) {
    _level = level;
  }

  /** 
   * @param level the minimum optimization level at which the branch 
   *              optimizations should be performed.
   * @param restrictCondBranchOpts Should optimization on conditional
   *              branches be restricted 
   */
  OPT_BranchOptimizationDriver(int level,boolean restrictCondBranchOpts) {
    this(level);
    _restrictCondBranchOpts = restrictCondBranchOpts;
  }

  
  /** Interface */
  final boolean shouldPerform(OPT_Options options) {
    return  options.getOptLevel() >= _level;
  }

  final String getName() {
    return  "Branch Optimizations";
  }

  /**
   * This phase contains no per-compilation instance fields.
   */
  final OPT_CompilerPhase newExecution(OPT_IR ir) {
    return  this;
  }


  /**
   * Perform peephole branch optimizations.
   * 
   * @param ir the IR to optimize
   */
  public final void perform(OPT_IR ir) {
    perform(ir, true);
  }

  public final void perform(OPT_IR ir, boolean renumber) {
    maximizeBasicBlocks(ir);
    boolean didSomething = false;
    boolean didSomethingThisTime = true;
    while (didSomethingThisTime) {
      applyPeepholeBranchOpts(ir);
      didSomethingThisTime = removeUnreachableCode(ir);
      didSomething |= didSomethingThisTime;
    }
    if (didSomething)
      maximizeBasicBlocks(ir);
    if (renumber)
      ir.cfg.compactNodeNumbering();
  }

  /**
   * This pass performs peephole branch optimizations. 
   * See Muchnick ~p.590
   *
   * @param ir the IR to optimize
   */
  protected void applyPeepholeBranchOpts(OPT_IR ir) {
    for (OPT_BasicBlockEnumeration e = ir.getBasicBlocks(); 
	 e.hasMoreElements();) {
      OPT_BasicBlock bb = e.next();
      if (!bb.isEmpty()) {
	for (OPT_InstructionEnumeration ie = bb.enumerateBranchInstructions(); 
	     ie.hasMoreElements();) {
	  OPT_Instruction s = ie.next();
	  if (optimizeBranchInstruction(ir, s, bb)) {
	    // hack: we may have modified the instructions; start over
	    ie = bb.enumerateBranchInstructions();
	  }
	}
      }
    }
  }

  /**
   * This method actually does the work of attempting to
   * peephole optimize a branch instruction.
   * @param ir the containing IR
   * @param s the branch instruction to optimize
   * @param bb the containing basic block
   * @return true if an optimization was applied, false otherwise
   */
  protected abstract boolean optimizeBranchInstruction(OPT_IR ir,
						       OPT_Instruction s,
						       OPT_BasicBlock bb);

  /**
   * Remove unreachable code
   *
   * @param ir the IR to optimize
   * @return true if did something, false otherwise
   */
  protected final boolean removeUnreachableCode(OPT_IR ir) {
    boolean result = false;

    // (1) All code in a basic block after an unconditional 
    //     trap instruction is dead.
    for (OPT_Instruction s = ir.firstInstructionInCodeOrder(); 
	 s != null; 
	 s = s.nextInstructionInCodeOrder()) {
      if (Trap.conforms(s)) {
	OPT_Instruction p = s.nextInstructionInCodeOrder();
	if (p.operator() != BBEND) {
	  OPT_BasicBlock bb = s.getBasicBlock();
	  do {
	    OPT_Instruction q = p;
	    p = p.nextInstructionInCodeOrder();
	    q.remove();
	  } while (p.operator() != BBEND);
	  bb.recomputeNormalOut(ir);
	  result = true;
	}
      }
    }

    // (2) perform a Depth-first search of the control flow graph,
    //     and remove any nodes not reachable from entry.
    OPT_BasicBlock entry = ir.cfg.entry();
    ir.cfg.clearDFS();
    entry.sortDFS();
    for (OPT_SpaceEffGraphNode node = entry; node != null;) {
      // save it now before removeFromCFGAndCodeOrder nulls it out!!!
      OPT_SpaceEffGraphNode nextNode = node.getNext();         
      if (!node.dfsVisited()) {
        OPT_BasicBlock bb = (OPT_BasicBlock)node;
        ir.cfg.removeFromCFGAndCodeOrder(bb);
        result = true;
      }
      node = nextNode;
    }
    return  result;
  }

  /**
   * Merge adjacent basic blocks
   *
   * @param ir the IR to optimize
   */
  protected final void maximizeBasicBlocks(OPT_IR ir) {
    for (OPT_BasicBlock currBB = ir.cfg.firstInCodeOrder(); currBB != null;) {
      if (currBB.mergeFallThrough(ir)) {
	// don't advance currBB; it may have a new trivial fallthrough to swallow
      } else {
        currBB = currBB.nextBasicBlockInCodeOrder();
      }
    }
  }


  // Helper functions
  
  /**
   * Given an instruction s, return the first LABEL instruction
   * following s.
   */
  protected final OPT_Instruction firstLabelFollowing(OPT_Instruction s) {
    for (s = s.nextInstructionInCodeOrder(); s != null; 
	 s = s.nextInstructionInCodeOrder()) {
      if (s.operator() == LABEL) {
        return  s;
      }
    }
    return  null;
  }

  /**
   * Given an instruction s, return the first real (non-label) instruction
   * following s
   */
  protected final OPT_Instruction firstRealInstructionFollowing(OPT_Instruction s) {
    for (s = s.nextInstructionInCodeOrder(); 
	 s != null; 
	 s = s.nextInstructionInCodeOrder()) {
      if (s.operator() != LABEL && s.operator() != BBEND) {
        return  s;
      }
    }
    return  s;
  }


  /**
   * Get the next basic block if all conditional branches in bb are
   * <em> not </em> taken
   *
   * @param bb the basic block in question
   * @returns basic block
   */
  protected final OPT_BasicBlock getNotTakenNextBlock(OPT_BasicBlock bb) {
    OPT_Instruction last = bb.lastRealInstruction();
    if (Goto.conforms(last) || MIR_Branch.conforms(last)) {
      return  last.getBranchTarget();
    } else {
      return  bb.nextBasicBlockInCodeOrder();
    }
  }
}
