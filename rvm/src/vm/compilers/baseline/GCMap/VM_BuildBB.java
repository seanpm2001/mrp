/*
 * (C) Copyright IBM Corp. 2001
 */
/**
 * Analyze the byte codes and determine the boundaries of the 
 * basic blocks. Used for building the reference maps for a 
 * method.
 */

final class VM_BuildBB implements VM_BytecodeConstants {

  // ---------------- Static Class Fields --------------------

  // Types of Instructions 
  static private final byte NONBRANCH = 1;
  static private final byte CONDITIONAL_BRANCH = 2;
  static private final byte BRANCH = 3;  

  static private final boolean COLLECT_STATISTICS = false;

  // ---------------- Instance Data --------------------------

  //***************************************************************************//
  //                                                                           //
  //  Once the method determineTheBasicBlocks is complete, these 4 items       //
  //  basicBlocks, byteToBlockMap, numJsrs and gcPointCount will be            // 
  //  appropriately filled in. They will be accessed by VM_BuildReferenceMaps  //
  //  VM_BuildLiveRefMaps, so that the reference maps can be built.            //
  //                                                                           //
  //***************************************************************************//

  VM_BasicBlock             basicBlocks[];       // basic blocks of the byte code
  short                     byteToBlockMap[];    // identify which block a byte 
                                                 // is part of
  int                       numJsrs;             // Number of unique "jsr"
                                                 // targets processed      
  int                       gcPointCount;        // Number of GC points spotted

  // This variable is used in multiple methods of this class, make it accessible
  int bytelength; 

  // ----------------- Instance Methods ----------------------

  // Analyze the bytecodes and build the basic blocks with their predecessors.
  // The results will be used by VM_BuildReferenceMaps
  // 
  boolean 
  determineTheBasicBlocks(VM_Method method) {

   // Other local variables
   VM_ExceptionHandlerMap    exceptions;   // Used to get a hold of the try Start,
                                           // End and Handler lists
   int                       retList[];    // List of basic block numbers that 
                                           // end with a "ret" instruction.
   byte                      bytecodes[];  // The bytecodes being analyzed.
   VM_BasicBlock             currentBB;    // current basic block being processed
   byte                      lastInstrType;// type of the last instruction
   int                       lastInstrStart;// byte index where last instruction 
                                            // started

   //
   //  Initialization
   //

   int nextRetList = 0;
   numJsrs        = 0;
   gcPointCount   = 1;  // All methods have the possible thread switch in prologue

   bytecodes      = method.getBytecodes();
   bytelength     = bytecodes.length;

   byteToBlockMap = new short[bytelength];
   basicBlocks    = new VM_BasicBlock[2];  // many methods only have one block 
                                           // (+1 for EXIT)

   VM_BasicBlock.resetBlockNumber();

   exceptions = method.getExceptionHandlerMap();

   retList = null;

   // 
   //  Set up the EXIT basic block
   // 

   basicBlocks[VM_BasicBlock.EXITBLOCK] = 
        new VM_BasicBlock(bytelength,bytelength,VM_BasicBlock.EXITBLOCK);

   //
   // Get the first basic block
   //

   currentBB = new VM_BasicBlock(0);
   basicBlocks = addBasicBlock(currentBB, basicBlocks);
   currentBB.setState(VM_BasicBlock.METHODENTRY);
   lastInstrType = NONBRANCH;
   lastInstrStart = 0;

   if (exceptions != null) {
     // Get blocks for any handlers, which tend to not be a clear block boundaries
     //
     basicBlocks = setupHandlerBBs(exceptions, byteToBlockMap, basicBlocks);

     // Set up blocks for start of try block, which tend not be to at clear 
     // block boundaries
     //
     basicBlocks = setupTryStartBBs(exceptions, byteToBlockMap, basicBlocks);
   }

   //
   // Scan the bytecodes for this method
   //
   for (int index=0; index<bytelength; ) {
     
     // Determine if we are at a block boundary
     // We are at a block boundary if:
     //   1) non-branch instruction followed by a known block 
     //   2) last instruction was a conditional branch
     //   3) last instruction was a branch
     // Note that forward branches mean that the byteToBlockMap will have
     // a basic block value prior to us examining that destination byte code
     //
     if (lastInstrType == NONBRANCH) {
        
       if (byteToBlockMap[index] == VM_BasicBlock.NOTBLOCK) {
	  // Not a new block
         // Make note of current block 
         byteToBlockMap[index] = (short)currentBB.getBlockNumber();
	}
	else {
	  // Earlier forward branch must have started this block
	  currentBB.setEnd(lastInstrStart);
	  basicBlocks[byteToBlockMap[index]].addPredecessor(currentBB);
         currentBB = basicBlocks[byteToBlockMap[index]];
	}
     }
     else { // we are at a block boundary, last instr was some type of branch
	if (lastInstrType == CONDITIONAL_BRANCH) {
	  currentBB.setEnd(lastInstrStart);
	  // See if we need a new block
         if (byteToBlockMap[index] == VM_BasicBlock.NOTBLOCK) {
	    VM_BasicBlock newBB = new VM_BasicBlock(index);
	    basicBlocks = addBasicBlock(newBB, basicBlocks);
           newBB.addPredecessor(currentBB);
	    currentBB = newBB;
           // Make note of current block 
           byteToBlockMap[index] = (short)currentBB.getBlockNumber();
	  }
         else {
	    // From an earlier forward branch 
           basicBlocks[byteToBlockMap[index]].addPredecessor(currentBB);	    
	    currentBB = basicBlocks[byteToBlockMap[index]];
	  }
	}
	else {
	  if (lastInstrType == BRANCH) {
	    currentBB.setEnd(lastInstrStart);
	    // See if we need a new block
           if (byteToBlockMap[index] == VM_BasicBlock.NOTBLOCK) {
	      VM_BasicBlock newBB = new VM_BasicBlock(index);
	      basicBlocks = addBasicBlock(newBB, basicBlocks);
             currentBB = newBB;
            // Make note of current block 
            byteToBlockMap[index] = (short)currentBB.getBlockNumber();
	    }
           else {
	      // From an earlier forward branch 
             currentBB = basicBlocks[byteToBlockMap[index]];
	    }
	  }
	}
     }  
     // end of determining if at block boundary

     // Now examine this instruction 
        int opcode = ((int)bytecodes[index]) & 0xFF;

        // get length (known constant for most)
	//
        int oplength = JBC_length[ opcode ];
        lastInstrStart = index;      // Instruction starts here
	lastInstrType = NONBRANCH;   // assume it will be a non-branch

        switch (opcode) {
	case JBC_ifeq :
        case JBC_ifne :
        case JBC_iflt :
        case JBC_ifge :
        case JBC_ifgt :
        case JBC_ifle :
        case JBC_if_icmpeq :
        case JBC_if_icmpne :
        case JBC_if_icmplt :
        case JBC_if_icmpge :
        case JBC_if_icmpgt :
        case JBC_if_icmple :
        case JBC_if_acmpeq :
        case JBC_if_acmpne :
        case JBC_ifnull :
        case JBC_ifnonnull :
	  {
	    lastInstrType = CONDITIONAL_BRANCH;
            short offset = getShortOffset(index, bytecodes);
            if(offset < 0) gcPointCount++; // gc map required if backward edge
	    int branchtarget = index + offset;
            basicBlocks = processBranchTarget(index, branchtarget, 
					      byteToBlockMap, basicBlocks);
	    index = index + oplength;
	    break;
	  }
	       
        case JBC_jsr :
	  {
	    lastInstrType = BRANCH;
	    short offset = getShortOffset(index, bytecodes);
	    int branchtarget = index + offset;
	    basicBlocks = processBranchTarget(index, branchtarget, 
					      byteToBlockMap, basicBlocks);
	    int jsrentryBBNum = byteToBlockMap[branchtarget];
            VM_BasicBlock bb = basicBlocks[jsrentryBBNum];
            if ((bb.getState() & VM_BasicBlock.JSRENTRY) == 0) numJsrs++;
	    bb.setState(VM_BasicBlock.JSRENTRY);  
	    index = index + oplength;
	    gcPointCount = gcPointCount+1;
	    break;
	  }

        case JBC_jsr_w :
	  {
	    // As with jsr, but the offset is wide.
	    lastInstrType = BRANCH;
            int offset = getIntOffset(index, bytecodes);
	    int branchtarget = index + offset;
	    basicBlocks = processBranchTarget(index, branchtarget, 
					      byteToBlockMap, basicBlocks);
	    int jsrentryBBNum = byteToBlockMap[branchtarget];
            VM_BasicBlock bb = basicBlocks[jsrentryBBNum];
            if ((bb.getState() & VM_BasicBlock.JSRENTRY) == 0) numJsrs++;
	    bb.setState(VM_BasicBlock.JSRENTRY);  
	    index = index + oplength;
	    gcPointCount = gcPointCount+1;
	    break;
	  }

	case JBC_goto :
	  {
	    lastInstrType = BRANCH;
	    short offset = getShortOffset(index, bytecodes);
            if(offset < 0) gcPointCount++; // gc map required if backward edge
	    int branchtarget = index + offset;
	    basicBlocks = processBranchTarget(index, branchtarget, 
					      byteToBlockMap, basicBlocks);
	    index = index + oplength;
	    break;
	  }

        case JBC_goto_w :
	  {
	    // As with goto, but the offset is wide.
	    int offset = getIntOffset(index, bytecodes);
            if(offset < 0) gcPointCount++; // gc map required if backward edge
	    int branchtarget = index + offset;
	    basicBlocks = processBranchTarget(index, branchtarget, 
					      byteToBlockMap, basicBlocks);
	    index = index + oplength;
	    break;
	  }

        case JBC_tableswitch :
	  {
	    int j = index;           // save initial value
	    index = index + 1;           // space past op code
	    index = (((index + 3)/4)*4); // align to next word boundary
	    // get default offset and generate basic block at default offset
	    // getIntOffset expects byte before offset
	    //
	    int def = getIntOffset(index-1, bytecodes);  
	    basicBlocks = processBranchTarget(j,j+def, byteToBlockMap, 
					      basicBlocks);

	    // get low offset
	    index = index + 4;           // go past default br offset
	    int low = getIntOffset(index-1, bytecodes);
	    index = index + 4;           // space past low offset

	    // get high offset
	    int high = getIntOffset(index-1, bytecodes);
	    index = index + 4;           // go past high offset

	    // generate labels for offsets
	    for (int k = 0; k < (high - low +1); k++) {
	      int l = index + k*4; // point to next offset
              // get next offset
              int offset = getIntOffset(l-1, bytecodes);
	      basicBlocks = processBranchTarget(j, j+offset, 
						  byteToBlockMap, basicBlocks);
	    }

	    index = index + (high - low +1) * 4; // space past offsets
	    break;
	  }
	  
        case JBC_lookupswitch :
	  {
	    int j = index;           // save initial value for labels
	    index = index +1;            // space past op code
	    index = (((index + 3)/4)*4); // align to next word boundary
	    // get default offset and
	    // process branch to default branch point
	    //
	    int def = getIntOffset(index-1, bytecodes);
	    basicBlocks = processBranchTarget(j, j+def, byteToBlockMap, 
					      basicBlocks);
	    index = index + 4;           // go past default  offset

	    // get number of pairs
	    int npairs = getIntOffset(index-1, bytecodes);
	    index = index + 4;           // space past  number of pairs

	    // generate label for each offset in table
	    for (int k = 0; k < npairs; k++) {
	      int l = index + k*8 + 4; // point to next offset
	      // get next offset
              int offset = getIntOffset(l-1, bytecodes);
	      basicBlocks = processBranchTarget(j, j+offset, byteToBlockMap, 
						basicBlocks);
	    }
	    index = index + (npairs) *8; // space past match-offset pairs
	    break;
	  }
        case JBC_wide :
	  {
	    int wopcode = ((int)(bytecodes[index+1])) & 0xFF;
	    if (wopcode == JBC_iinc)
	      index = index + 6;
	    else
	      index = index + 4;
	    break;
	  }

	case JBC_ireturn :
 	case JBC_lreturn :
	case JBC_freturn :
	case JBC_dreturn :
	case JBC_areturn :
	case JBC_return  :
	  {
	    lastInstrType = BRANCH;
            index = index + oplength;
            basicBlocks[VM_BasicBlock.EXITBLOCK].addPredecessor(currentBB); 
	    if (method.isSynchronized() || VM.UseEpilogueYieldPoints)
	      gcPointCount++;
            break;
	  }

	case JBC_ret :
	  {
	    lastInstrType = BRANCH;
	    int variableNum = ((int)bytecodes[index + 1]) & 0xFF; 
	    int blocknum = currentBB.getBlockNumber();
            basicBlocks[blocknum].setState(VM_BasicBlock.JSREXIT);

	    // Worry about growing retListarray
	    if (retList == null) retList = new int[10];
	    if (nextRetList >= retList.length) {
	      int[] biggerRetList = new int[nextRetList + 10];
	      for (int i=0; i<nextRetList; i++) 
		 biggerRetList[i] = retList[i];
	      retList = biggerRetList;
	      biggerRetList = null;
	    }
	    retList[nextRetList++] = blocknum;	  
            index = index + oplength;
            break;
	  }

	case JBC_athrow :
	   {
	     lastInstrType = BRANCH;
	     processAthrow(exceptions, index, basicBlocks, byteToBlockMap);
             index = index + oplength;
	     gcPointCount++;
             break;
	   }

        case JBC_aaload :
        case JBC_iaload :
        case JBC_faload :
        case JBC_baload :
        case JBC_caload :
        case JBC_saload :
        case JBC_laload :
        case JBC_daload :
        case JBC_lastore :
        case JBC_dastore :
        case JBC_iastore :
        case JBC_fastore :
        case JBC_aastore :
        case JBC_bastore :
        case JBC_castore :
        case JBC_sastore :
        case JBC_putfield :
        case JBC_getfield :
	case JBC_getstatic :
	case JBC_putstatic :
        case JBC_irem :
        case JBC_idiv :
        case JBC_lrem :
        case JBC_ldiv :
        case JBC_invokevirtual :
        case JBC_invokespecial :
        case JBC_invokestatic  :
        case JBC_invokeinterface :
	case JBC_instanceof :
	case JBC_checkcast  :
	case JBC_monitorenter :
	case JBC_monitorexit  :
        case JBC_new :
	case JBC_newarray  :
	case JBC_anewarray :
	case JBC_multianewarray :
	  {
	    index = index + oplength;
	    gcPointCount = gcPointCount+1;
	    break;
	  }

	default :
          {
	    if (oplength == 0) 
	      throw new ClassFormatError("Unknown opcode:" + opcode);
	    byteToBlockMap[index] = (short)currentBB.getBlockNumber(); 
            index = index + oplength;
            break;
          }
        } // switch (opcode) 
   } // for (index=0; ...

   currentBB.setEnd(lastInstrStart);   // close off last block

   if (exceptions != null) {
     // process catch blocks
     processExceptionHandlers(exceptions, byteToBlockMap, basicBlocks);

     // mark all blocks in try sections as being part of a try
     markTryBlocks(exceptions, byteToBlockMap, basicBlocks);

     // process ret instructions as last step
     if ( retList != null)  
       processRetList(basicBlocks, retList, nextRetList, byteToBlockMap);
   }

   boolean returnRunLive = false;

   // With the analysis complete, determine if live analysis is pauseable
   if (VM.LiveReferenceMaps && retList == null && exceptions == null)
     returnRunLive = true;

   return returnRunLive;      

  } // end BuildBB(...)


                   /********************************/
                   /*                              */
                   /*   Routines for Branches      */
                   /*                              */
                   /********************************/

  // Processing a branch that appears at location index in the byte code and has a 
  // target index of branchtarget in the byte code. The target of a branch must 
  // start a basic block. So if the byteToBlockMap doesn't already show a basic 
  // block at the target, make one start there. If a basic block is already set 
  // up and this is a branch forward then only need to adjust predecessor list
  // (we know it is not a branch into the middle of a block as only starts are 
  // marked in byte code beyond "index"). If the basic block is already set up and
  // this is a backward branch then we must check if the block needs splitting,
  // branching to the middle of a block is not allowed.
  //
  private VM_BasicBlock[]
  processBranchTarget(int index, int branchtarget, short byteToBlockMap[], 
                      VM_BasicBlock basicBlocks[]) {

    VM_BasicBlock newBB, currentBB;
    if (byteToBlockMap[branchtarget] == VM_BasicBlock.NOTBLOCK) {
      newBB = new VM_BasicBlock(branchtarget);
      basicBlocks = addBasicBlock(newBB, basicBlocks);		 
      byteToBlockMap[branchtarget] = (short)newBB.getBlockNumber();
      currentBB = basicBlocks[byteToBlockMap[index]];
      newBB.addPredecessor(currentBB);
    }
    else if (index > branchtarget) {
      // This is a backwards branch
      basicBlocks = processBackwardBranch(index, branchtarget, 
					  byteToBlockMap, basicBlocks);
    }
    else {
      // This is a forward branch to an existing block, need to register 
      // the predecessor
      currentBB = basicBlocks[byteToBlockMap[index]];
      basicBlocks[byteToBlockMap[branchtarget]].addPredecessor(currentBB);
    }
    return basicBlocks;
  }

  // A backwards branch has been found from the byte code at location "index" 
  // to a target location of "branchtarget". Need to make sure that the 
  // branchtarget location is the start of a block (and if not, then split the 
  // existing block into two) Need to register the block that ends at "index" 
  // as a predecessor of the block that starts at branchtarget.
  //
  private VM_BasicBlock[] 
  processBackwardBranch(int index, int branchtarget, short b2BBMap[], 
                        VM_BasicBlock basicBlocks[]) {

    VM_BasicBlock existingBB, currentBB, newBB, targetBB;
    int newBlockNum, i, newBlockEnd;

    existingBB = basicBlocks[b2BBMap[branchtarget]];
    if (existingBB.getStart() != branchtarget) {

      // Need to split the existing block in two, by ending the existing block
      // at the previous instruction and starting a new block at the branchtarget
      // Need to split the existing block in two. It is best to set up the new
      // block to end at the instruction before the target and the existing
      // block to start at the target. That way the tail stays the same. 

      newBB = new VM_BasicBlock(existingBB.getStart());
      basicBlocks = addBasicBlock(newBB, basicBlocks);
      newBlockNum = newBB.getBlockNumber();

      existingBB.setStart(branchtarget);

      // Find the last instruction prior to the branch target;
      //  that's the end of the new block
      //
      for (i=branchtarget-1; b2BBMap[i]==VM_BasicBlock.NOTBLOCK; i--);

      newBlockEnd = i;
      newBB.setEnd(i);

      // Going forwards, mark the start of each instruction with the new block 
      // number
      //
      for (i=newBB.getStart(); i<=newBlockEnd; i++) {
	if (b2BBMap[i] != VM_BasicBlock.NOTBLOCK)
	  b2BBMap[i]= (short)newBlockNum;
      }

      VM_BasicBlock.transferPredecessors(existingBB, newBB);

      // The new block is a predecessor of the existing block
      existingBB.addPredecessor(newBB);

    }
    else {}  // Nice coincidence, the existing block starts at "branchtarget"


    // Now mark the "current" block (the one that ends at "index") as a predecessor
    // of the target block (which is either the existing block or a newly made 
    // block)
    //
    currentBB = basicBlocks[b2BBMap[index]];
    existingBB.addPredecessor(currentBB);
    return basicBlocks;    
  }


                   /********************************/
                   /*                              */
                   /*   Routines for JSR/Ret       */
                   /*                              */
                   /********************************/

  // process the effect of the ret instructions on the precedance table
  //
  private void 
  processRetList(VM_BasicBlock basicBlocks[], int retList[], int nextRetList, 
		 short b2BBMap[]) {
    // block 0 not used
    int otherRetCount;
    for ( int i = 0; i < nextRetList; i++) {
      int retBlockNum       = retList[i];
      VM_BasicBlock retBB   = basicBlocks[retBlockNum];
      boolean[] seenAlready = new boolean[VM_BasicBlock.getNumberofBlocks()+1]; 
      otherRetCount = 0;
      findAndSetJSRCallSite(retBlockNum, retBB, otherRetCount, basicBlocks, 
			    seenAlready, b2BBMap);
    }
  }

  // scan back from ret instruction to jsr call sites 
  //
  private void 
  findAndSetJSRCallSite(int  pred, VM_BasicBlock retBB, int otherRetCount, 
			VM_BasicBlock basicBlocks[], boolean seenAlready[], 
			short b2BBMap[]) {
   seenAlready[pred] = true;
   VM_BasicBlock jsrBB =  basicBlocks[pred]; 
   jsrBB.setState(VM_BasicBlock.INJSR);       

   if (basicBlocks[pred].isJSRExit() && pred != retBB.getBlockNumber())
     otherRetCount++;

   if (basicBlocks[pred].isJSREntry()) {
     if (otherRetCount == 0) {
       // setup call site
       setupJSRCallSite(basicBlocks[pred], retBB, basicBlocks, b2BBMap); 
       return;  
     }
     else
       otherRetCount--;
   }
   int[] preds = basicBlocks[pred].getPredecessors();
   for( int i = 0; i < preds.length; i++) {
     int pred2 = preds[i];
     if (!seenAlready[pred2])
       findAndSetJSRCallSite(pred2,retBB,otherRetCount, basicBlocks, seenAlready, 
			     b2BBMap);
   }
  } // findAndSetJSRCallSite

  // setup jsr call site
  //
  private void
  setupJSRCallSite(VM_BasicBlock entryBB, VM_BasicBlock retBB, 
		   VM_BasicBlock basicBlocks[], short b2BBMap[]) {

    int newBB;
    int[] callsites = entryBB.getPredecessors();
    int callLength = callsites.length;
    for( int i = 0; i < callLength; i++){
      int callsite = callsites[i];
      int blockend = basicBlocks[callsite].getEnd();
      for (newBB = blockend+1; b2BBMap[newBB] == VM_BasicBlock.NOTBLOCK; newBB++);
      int nextBlock = b2BBMap[newBB];
      basicBlocks[nextBlock].addPredecessor(retBB);
    }
    return;
  }

                   /********************************/
                   /*                              */
                   /*   Routines for Try/catch     */
                   /*                              */
                   /********************************/

  // For every handler, make a block that starts with the handler PC
  // Only called when exceptions is not null.
  //
  private VM_BasicBlock[]
  setupHandlerBBs(VM_ExceptionHandlerMap exceptions, short b2BBMap[], 
                  VM_BasicBlock basicBlocks[]) {

   int[] tryHandlerPC = exceptions.getHandlerPC();
   int tryLength = tryHandlerPC.length;
   VM_BasicBlock handlerBB;

   for (int i=0; i<tryLength; i++) {
     if (b2BBMap[tryHandlerPC[i]] == VM_BasicBlock.NOTBLOCK) {
       handlerBB = new VM_BasicBlock(tryHandlerPC[i]);
       handlerBB.setState(VM_BasicBlock.TRYHANDLERSTART);
       basicBlocks = addBasicBlock(handlerBB, basicBlocks); 
       b2BBMap[tryHandlerPC[i]] = (short)handlerBB.getBlockNumber();
     }
   }
   return basicBlocks;
  }

  // For every try start, make a block that starts with the Try start,
  // mark it as a try start. Only called when exceptions is not null.
  //
  private VM_BasicBlock[]
  setupTryStartBBs(VM_ExceptionHandlerMap exceptions, short b2BBMap[], 
                   VM_BasicBlock basicBlocks[]) {

   int[] tryStartPC = exceptions.getStartPC();
   int tryLength = tryStartPC.length;
   VM_BasicBlock tryStartBB;
 
   for (int i=0; i< tryLength; i++) {
    if (b2BBMap[tryStartPC[i]] == VM_BasicBlock.NOTBLOCK) {
       tryStartBB = new VM_BasicBlock(tryStartPC[i]);
       basicBlocks = addBasicBlock(tryStartBB, basicBlocks); 
       b2BBMap[tryStartPC[i]] = (short)tryStartBB.getBlockNumber();
       tryStartBB.setState(VM_BasicBlock.TRYSTART);
    }
   }
   return basicBlocks;
  }

  // For every handler, mark the blocks in its try block as its predecessors.
  // Only called when exceptions is not null.
  //
  private void
  processExceptionHandlers(VM_ExceptionHandlerMap exceptions,short b2BBMap[], 
                           VM_BasicBlock basicBlocks[]) {
    int[] tryStartPC = exceptions.getStartPC();
    int[] tryEndPC = exceptions.getEndPC();
    int[] tryHandlerPC = exceptions.getHandlerPC();
    int tryLength = tryHandlerPC.length;
    int handlerBBNum, throwBBNum;
    VM_BasicBlock tryHandlerBB, throwBB;

    for (int i=0; i< tryLength; i++) {
        handlerBBNum = b2BBMap[tryHandlerPC[i]];
	tryHandlerBB = basicBlocks[handlerBBNum];
	throwBBNum = 0;
	for (int k=tryStartPC[i]; k < tryEndPC[i]; k++) {
	  if (b2BBMap[k] == VM_BasicBlock.NOTBLOCK) continue;

	  if (b2BBMap[k] != throwBBNum) {
	    throwBBNum = b2BBMap[k];
	    throwBB = basicBlocks[throwBBNum];
	    tryHandlerBB.addUniquePredecessor(throwBB);
	  }
	}
    }
  }

  // Mark all the blocks within try range as being Try blocks
  // used for determining the stack maps for Handler blocks
  // Only called when exceptions is not null.
  //
  private void
  markTryBlocks(VM_ExceptionHandlerMap exceptions, short b2BBMap[], 
		VM_BasicBlock basicBlocks[]) {
    int[] tryStartPC = exceptions.getStartPC();
    int[] tryEndPC = exceptions.getEndPC();
    int tryLength = tryStartPC.length;
    int tryBlockNum = 0;

    for (int i=0; i< tryLength; i++) 
      for (int j=tryStartPC[i]; j< tryEndPC[i]; j++) {
	if (b2BBMap[j] != VM_BasicBlock.NOTBLOCK) {
	  if (tryBlockNum != b2BBMap[j]) {
	    tryBlockNum = b2BBMap[j];
	    basicBlocks[tryBlockNum].setState(VM_BasicBlock.TRYBLOCK);
	  }
	}
      }
  }

  // Check if an athrow is within a try block, if it is, then handlers have this
  // block as their predecessor; which is registered in "processExceptionHandlers"
  // Otherwise, the athrow acts as a branch to the exit and that should be marked 
  // here. Note exceptions may be null. 
  // 
  private void 
  processAthrow(VM_ExceptionHandlerMap exceptions, int athrowIndex, 
		VM_BasicBlock basicBlocks[], short b2BBMap[]) {

    boolean notfound = true;

    if (exceptions != null) {
      int[] tryStartPC   = exceptions.getStartPC();
      int[] tryEndPC     = exceptions.getEndPC();
      int   tryLength    = tryStartPC.length;
  
      // Check if this athrow index is within any of the try blocks
      for (int i=0; i<tryLength; i++) {
	if (tryStartPC[i] <= athrowIndex && athrowIndex < tryEndPC[i]) {
	  notfound = false;
	  break;
	}
      }
    } 

    if (notfound) {
      VM_BasicBlock athrowBB = basicBlocks[b2BBMap[athrowIndex]];
      basicBlocks[VM_BasicBlock.EXITBLOCK].addPredecessor(athrowBB);
    }

  }

                   /********************************/
                   /*                              */
                   /*   Misc routines              */
                   /*                              */
                   /********************************/

  // add a basic block to the list
  //

  private VM_BasicBlock[] addBasicBlock(VM_BasicBlock newBB, 
					VM_BasicBlock basicBlocks[]) {
    int blocknum;

    // Check whether basicBlock array must be grown.
    //
    blocknum = newBB.getBlockNumber();
    if (blocknum >= basicBlocks.length) {
      int currentSize = basicBlocks.length;
      int newSize = 15;
      if (currentSize!=2) {
	if (currentSize==15)
	  newSize = bytelength >> 4; // assume 16 bytecodes per basic block
	else
	  newSize = currentSize + currentSize >> 3;  // increase by 12.5%
	if (newSize <= blocknum)
	  newSize = blocknum + 20;
      }

      VM_BasicBlock biggerBlocks[] = new VM_BasicBlock[newSize];
      for (int i=0; i<currentSize; i++) 
	 biggerBlocks[i] = basicBlocks[i];
      basicBlocks = biggerBlocks;
      biggerBlocks = null;
    } // if (blocknum >= basicBlocks.length

    // Go ahead and add block
    basicBlocks[blocknum] = newBB;	  
    return basicBlocks;
  }


  // get 2 byte offset
  //
  private short 
  getShortOffset(int index, byte bytecodes[]) {
       return (short)((((int)bytecodes[index+1]) << 8) | 
		      (((int)bytecodes[index+2]) & 0xFF));
  }

  // get 4 byte offset for wide instructions
  //
  private int 
  getIntOffset(int index, byte bytecodes[]) {
        return (int)((((int)bytecodes[index+1]) << 24) | 
		     ((((int)bytecodes[index+2]) & 0xFF) << 16) |
                     ((((int)bytecodes[index+3]) & 0xFF) << 8) | 
		     (((int)bytecodes[index+4]) & 0xFF));
  }

}


