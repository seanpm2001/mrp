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
package org.jikesrvm.compilers.common.assembler.ppc;

import org.jikesrvm.VM;
import org.jikesrvm.architecture.MachineRegister;
import org.jikesrvm.compilers.baseline.ppc.BaselineCompilerImpl;
import org.jikesrvm.compilers.common.assembler.AbstractAssembler;
import org.jikesrvm.compilers.common.assembler.ForwardReference;
import org.jikesrvm.compilers.common.CodeArray;
import org.jikesrvm.objectmodel.JavaHeader;
import org.jikesrvm.ppc.BaselineConstants;
import org.jikesrvm.ppc.Disassembler;
import org.jikesrvm.ppc.RegisterConstants;
import org.jikesrvm.ppc.RegisterConstants.CR;
import org.jikesrvm.ppc.RegisterConstants.GPR;
import org.jikesrvm.ppc.RegisterConstants.FPR;
import org.jikesrvm.ppc.StackframeLayoutConstants;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.util.Services;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

/**
 * Machine code generators:
 *
 * Corresponding to a PowerPC assembler instruction of the form
 *    xx A,B,C
 * there will be a method
 *    void emitXX (GPR A, GPR B, GPR C).
 *
 * The emitXX method appends this instruction to an MachineCode object.
 * The name of a method for generating assembler instruction with the record
 * bit set (say xx.) will be end in a lower-case r (emitXXr).
 *
 * mIP will be incremented to point to the next machine instruction.
 *
 * Machine code generators:
 */
public final class Assembler extends AbstractAssembler implements BaselineConstants, AssemblerConstants {

  /**
   * The array holding the generated binary code.
   */
  private int[] machineCodes;

  /** Debug output? */
  private final boolean shouldPrint;
  /**  // Baseline compiler instance for this assembler.  May be null. */
  final BaselineCompilerImpl compiler;
  /** current machine code instruction */
  private int mIP;
  /** Should the generated code be regarded as hot? */
  private final boolean isHot = false;

  public Assembler(int length) {
    this(length, false, null);
  }

  public Assembler(int length, boolean sp, BaselineCompilerImpl comp) {
    shouldPrint = sp;
    compiler = comp;
    mIP = 0;
  }

  public Assembler(int length, boolean sp) {
    this(length, sp, null);
  }

  private static int maskLower16(Offset val) {
    return (val.toInt() & 0xFFFF);
  }

  public static int maskUpper16(Offset val) {
    return maskUpper16(val.toInt());
  }

  public static int maskUpper16(int val) {
    short s = (short) (val & 0xFFFF);
    return ((val - (int) s) >>> 16);
  }

  public static boolean fits(Offset val, int bits) {
    Word o = val.toWord().rsha(bits - 1);
    return (o.isZero() || o.isMax());
  }

  public static boolean fits(long val, int bits) {
    val = val >> bits - 1;
    return (val == 0L || val == -1L);
  }

  public static boolean fits(int val, int bits) {
    val = val >> bits - 1;
    return (val == 0 || val == -1);
  }

  public void noteBytecode(int i, String bcode) {
    String s1 = Services.getHexString(mIP << LG_INSTRUCTION_WIDTH, true);
    VM.sysWrite(s1 + ": [" + i + "] " + bcode + "\n");
  }

  /* Handling backward branch references */

  /**
   * Return a copy of the generated code as a CodeArray.
   * @return a copy of the generated code as a CodeArray.
   */
  public final CodeArray getMachineCodes () {
    int len = getMachineCodeIndex();
    CodeArray trimmed = CodeArray.Factory.create(len, isHot);
    for (int i=0; i<len; i++) {
      trimmed.set(i, machineCodes[i]);
    }
    return trimmed;
  }

  public int getMachineCodeIndex() {
    return mIP;
  }

  private void appendInstruction(int instr) {
    machineCodes[mIP] = instr;
    mIP++;
  }

  /* Handling forward branch references */

  ForwardReference forwardRefs = null;

  /* call before emiting code for the branch */
  final void reserveForwardBranch(int where) {
    ForwardReference fr = new ForwardReference.UnconditionalBranch(mIP, where);
    forwardRefs = ForwardReference.enqueue(forwardRefs, fr);
  }

  /* call before emiting code for the branch */
  final void reserveForwardConditionalBranch(int where) {
    emitNOP();
    ForwardReference fr = new ForwardReference.ConditionalBranch(mIP, where);
    forwardRefs = ForwardReference.enqueue(forwardRefs, fr);
  }

  /* call before emiting code for the branch */
  final void reserveShortForwardConditionalBranch(int where) {
    ForwardReference fr = new ForwardReference.ConditionalBranch(mIP, where);
    forwardRefs = ForwardReference.enqueue(forwardRefs, fr);
  }

  /* call before emiting data for the case branch */
  final void reserveForwardCase(int where) {
    ForwardReference fr = new ForwardReference.SwitchCase(mIP, where);
    forwardRefs = ForwardReference.enqueue(forwardRefs, fr);
  }

  /* call before emiting code for the target */
  public final void resolveForwardReferences(int label) {
    if (forwardRefs == null) return;
    forwardRefs = ForwardReference.resolveMatching(this, forwardRefs, label);
  }

  public final void patchUnconditionalBranch(int sourceMachinecodeIndex) {
    int delta = mIP - sourceMachinecodeIndex;
    int instr = machineCodes[sourceMachinecodeIndex];
    if (VM.VerifyAssertions) VM._assert((delta >>> 23) == 0); // delta (positive) fits in 24 bits
    instr |= (delta << 2);
    machineCodes[sourceMachinecodeIndex] = instr;
  }

  public final void patchConditionalBranch(int sourceMachinecodeIndex) {
    final int Btemplate = 18 << 26;
    int delta = mIP - sourceMachinecodeIndex;
    int instr = machineCodes[sourceMachinecodeIndex];
    if ((delta >>> 13) == 0) { // delta (positive) fits in 14 bits
      instr |= (delta << 2);
      machineCodes[sourceMachinecodeIndex] = instr;
    } else {
      if (VM.VerifyAssertions) VM._assert((delta >>> 23) == 0); // delta (positive) fits in 24 bits
      instr ^= 0x01000008; // make skip instruction with opposite sense
      machineCodes[sourceMachinecodeIndex-1] = instr; // skip unconditional branch to target
      machineCodes[sourceMachinecodeIndex] = Btemplate | (delta & 0xFFFFFF) << 2;
    }
  }

  public final void patchShortBranch(int sourceMachinecodeIndex) {
    int delta = mIP - sourceMachinecodeIndex;
    int instr = machineCodes[sourceMachinecodeIndex];
    if ((delta >>> 13) == 0) { // delta (positive) fits in 14 bits
      instr |= (delta << 2);
      machineCodes[sourceMachinecodeIndex] = instr;
    } else {
      throw new InternalError("Long offset doesn't fit in short branch\n");
    }
  }

  public final void registerLoadReturnAddress(int bReturn) {
    ForwardReference r = new ForwardReference.LoadReturnAddress(mIP, bReturn);
    forwardRefs = ForwardReference.enqueue(forwardRefs, r);
  }

  /* the prologue is always before any real bytecode index.
  *
  * CAUTION: the machine code to be patched has following pattern:
  *          BL 4
  *          MFLR T1                   <- address in LR
  *          ADDI  T1, offset, T1       <- toBePatchedMCAddr
  *          STU
  *
  * The third instruction should be patched with accurate relative address.
  * It is computed by (mIP - sourceIndex + 1)*4;
  */
  public final void patchLoadReturnAddress(int sourceIndex) {
    int offset = (mIP - sourceIndex + 1) * 4;
    int mi = ADDI(T1, offset, T1);
    machineCodes[sourceIndex] = mi;
  }

  final int ADDI(GPR RT, int D, GPR RA) {
    final int ADDItemplate = 14 << 26;
    return ADDItemplate | RT.value() << 21 | RA.value() << 16 | (D & 0xFFFF);
  }

  public final ForwardReference generatePendingJMP(int bTarget) {
    return this.emitForwardB();
  }

  /************ OSR Support */

  public final void patchSwitchCase(int sourceMachinecodeIndex) {
    int delta = (mIP - sourceMachinecodeIndex) << 2;
    // correction is number of bytes of source off switch base
    int correction = (int) machineCodes[sourceMachinecodeIndex];
    int offset = delta + correction;
    machineCodes[sourceMachinecodeIndex] = offset;
  }

  /* machine instructions */

  public final void emitADD(GPR RT, GPR RA, GPR RB) {
    final int ADDtemplate = 31 << 26 | 10 << 1;
    int mi = ADDtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitADDE(GPR RT, GPR RA, GPR RB) {
    final int ADDEtemplate = 31 << 26 | 138 << 1;
    int mi = ADDEtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitADDICr(GPR RT, GPR RA, int SI) {
    final int ADDICrtemplate = 13 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(SI, 16));
    int mi = ADDICrtemplate | RT.value() << 21 | RA.value() << 16 | (SI & 0xFFFF);
    
    appendInstruction(mi);
  }

  public final void emitAND(GPR RA, GPR RS, GPR RB) {
    final int ANDtemplate = 31 << 26 | 28 << 1;
    int mi = ANDtemplate | RS.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitANDI(GPR RA, GPR RS, int U) {
    final int ANDItemplate = 28 << 26;
    if (VM.VerifyAssertions) VM._assert((U >>> 16) == 0);
    int mi = ANDItemplate | RS.value() << 21 | RA.value() << 16 | U;
    
    appendInstruction(mi);
  }

  public final void emitANDIS(GPR RA, GPR RS, int U) {
    final int ANDIStemplate = 29 << 26;
    if (VM.VerifyAssertions) VM._assert((U & 0xffff) == 0);
    int mi = ANDIStemplate | RS.value() << 21 | RA.value() << 16 | (U >>> 16);
    
    appendInstruction(mi);
  }

  private void _emitB(int relative_address) {
    final int Btemplate = 18 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(relative_address, 24));
    int mi = Btemplate | (relative_address & 0xFFFFFF) << 2;
    
    appendInstruction(mi);
  }

  public final void emitB(int relative_address, int label) {
    if (relative_address == 0) {
      reserveForwardBranch(label);
    } else {
      relative_address -= mIP;
    }
    _emitB(relative_address);
  }

  public final void emitB(int relative_address) {
    relative_address -= mIP;
    if (VM.VerifyAssertions) VM._assert(relative_address < 0);
    _emitB(relative_address);
  }

  public final ForwardReference emitForwardB() {
    ForwardReference fr;
    if (compiler != null) {
      fr = new AssemblerShortBranch(mIP, compiler.spTopOffset);
    } else {
      fr = new ForwardReference.ShortBranch(mIP);
    }
    _emitB(0);
    return fr;
  }

  public final void emitBLA(int address) {
    final int BLAtemplate = 18 << 26 | 3;
    if (VM.VerifyAssertions) VM._assert(fits(address, 24));
    int mi = BLAtemplate | (address & 0xFFFFFF) << 2;
    
    appendInstruction(mi);
  }

  private void _emitBL(int relative_address) {
    final int BLtemplate = 18 << 26 | 1;
    if (VM.VerifyAssertions) VM._assert(fits(relative_address, 24));
    int mi = BLtemplate | (relative_address & 0xFFFFFF) << 2;
    
    appendInstruction(mi);
  }

  public final void emitBL(int relative_address, int label) {
    if (relative_address == 0) {
      reserveForwardBranch(label);
    } else {
      relative_address -= mIP;
    }
    _emitBL(relative_address);
  }

  public final ForwardReference emitForwardBL() {
    ForwardReference fr;
    if (compiler != null) {
      fr = new AssemblerShortBranch(mIP, compiler.spTopOffset);
    } else {
      fr = new ForwardReference.ShortBranch(mIP);
    }
    _emitBL(0);
    return fr;
  }

  public static int flipCode(int cc) {
    switch (cc) {
      case LT:
        return GE;
      case GT:
        return LE;
      case EQ:
        return NE;
      case LE:
        return GT;
      case GE:
        return LT;
      case NE:
        return EQ;
    }
    if (VM.VerifyAssertions) VM._assert(false);
    return -1;
  }

  private void _emitBC(int cc, int relative_address) {
    final int BCtemplate = 16 << 26;
    if (fits(relative_address, 14)) {
      int mi = BCtemplate | cc | (relative_address & 0x3FFF) << 2;
      
      appendInstruction(mi);
    } else {
      _emitBC(flipCode(cc), 2);
      _emitB(relative_address - 1);
    }
  }

  public final void emitBC(int cc, int relative_address, int label) {
    if (relative_address == 0) {
      reserveForwardConditionalBranch(label);
    } else {
      relative_address -= mIP;
    }
    _emitBC(cc, relative_address);
  }

  public final void emitShortBC(int cc, int relative_address, int label) {
    if (relative_address == 0) {
      reserveShortForwardConditionalBranch(label);
    } else {
      relative_address -= mIP;
    }
    _emitBC(cc, relative_address);
  }

  public final void emitBC(int cc, int relative_address) {
    relative_address -= mIP;
    if (VM.VerifyAssertions) VM._assert(relative_address < 0);
    _emitBC(cc, relative_address);
  }

  public final ForwardReference emitForwardBC(int cc) {
    ForwardReference fr;
    if (compiler != null) {
      fr = new AssemblerShortBranch(mIP, compiler.spTopOffset);
    } else {
      fr = new ForwardReference.ShortBranch(mIP);
    }
    _emitBC(cc, 0);
    return fr;
  }

  // delta i: difference between address of case i and of delta 0
  public final void emitSwitchCase(int i, int relative_address, int bTarget) {
    int data = i << 2;
    if (relative_address == 0) {
      reserveForwardCase(bTarget);
    } else {
      data += ((relative_address - mIP) << 2);
    }
    
    appendInstruction(data);
  }

  public final void emitBCLR() {
    final int BCLRtemplate = 19 << 26 | 0x14 << 21 | 16 << 1;
    int mi = BCLRtemplate;
    
    appendInstruction(mi);
  }

  public final void emitBCLRL() {
    final int BCLRLtemplate = 19 << 26 | 0x14 << 21 | 16 << 1 | 1;
    int mi = BCLRLtemplate;
    
    appendInstruction(mi);
  }

  public final void emitBCCTR() {
    final int BCCTRtemplate = 19 << 26 | 0x14 << 21 | 528 << 1;
    int mi = BCCTRtemplate;
    
    appendInstruction(mi);
  }

  public final void emitBCCTRL() {
    final int BCCTRLtemplate = 19 << 26 | 0x14 << 21 | 528 << 1 | 1;
    int mi = BCCTRLtemplate;
    
    appendInstruction(mi);
  }

  public final void emitADDI(GPR RT, int D, GPR RA) {
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    _emitADDI(RT, D & 0xFFFF, RA);
  }

  private void _emitADDI(GPR RT, int D, GPR RA) {
    final int ADDItemplate = 14 << 26;
    //D has already been masked
    int mi = ADDItemplate | RT.value() << 21 | RA.value() << 16 | D;
    
    appendInstruction(mi);
  }

  public final void emitADDI(GPR RT, Offset off, GPR RA) {
    if (VM.VerifyAssertions) VM._assert(fits(off, 16));
    _emitADDI(RT, maskLower16(off), RA);
  }

  public final void emitADDIS(GPR RT, GPR RA, int UI) {
    if (VM.VerifyAssertions) VM._assert(UI == (UI & 0xFFFF));
    _emitADDIS(RT, RA, UI);
  }

  private void _emitADDIS(GPR RT, GPR RA, int UI) {
    final int ADDIStemplate = 15 << 26;
    //UI has already been masked
    int mi = ADDIStemplate | RT.value() << 21 | RA.value() << 16 | UI;
    
    appendInstruction(mi);
  }

  public final void emitADDIS(GPR RT, int UI) {
    if (VM.VerifyAssertions) VM._assert(UI == (UI & 0xFFFF));
    _emitADDIS(RT, UI);
  }

  private void _emitADDIS(GPR RT, int UI) {
    final int ADDIStemplate = 15 << 26;
    //UI has already been masked
    int mi = ADDIStemplate | RT.value() << 21 | UI;
    
    appendInstruction(mi);
  }

  public final void emitCMP(int BF, GPR RA, GPR RB) {
    final int CMPtemplate = 31 << 26;
    int mi = CMPtemplate | BF << 23 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitCMP(GPR RA, GPR RB) {
    final int CMPtemplate = 31 << 26;
    int mi = CMPtemplate | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitCMPD(GPR RA, GPR RB) {
    final int CMPtemplate = 31 << 26;
    final int CMPDtemplate = CMPtemplate | 1 << 21;
    int mi = CMPDtemplate | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitCMPI(int BF, GPR RA, int V) {
    final int CMPItemplate = 11 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(V, 16));
    int mi = CMPItemplate | BF << 23 | RA.value() << 16 | (V & 0xFFFF);
    
    appendInstruction(mi);
  }

  public final void emitCMPI(GPR RA, int V) {
    final int CMPItemplate = 11 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(V, 16));
    int mi = CMPItemplate | RA.value() << 16 | (V & 0xFFFF);
    
    appendInstruction(mi);
  }

  public final void emitCMPDI(GPR RA, int V) {
    final int CMPItemplate = 11 << 26;
    final int CMPDItemplate = CMPItemplate | 1 << 21;
    if (VM.VerifyAssertions) VM._assert(fits(V, 16));
    int mi = CMPDItemplate | RA.value() << 16 | (V & 0xFFFF);
    
    appendInstruction(mi);
  }

  public final void emitCMPL(int BF, GPR RA, GPR RB) {
    final int CMPLtemplate = 31 << 26 | 32 << 1;
    int mi = CMPLtemplate | BF << 23 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitCMPL(GPR RA, GPR RB) {
    final int CMPLtemplate = 31 << 26 | 32 << 1;
    int mi = CMPLtemplate | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitCMPLD(GPR RA, GPR RB) {
    final int CMPLtemplate = 31 << 26 | 32 << 1;
    final int CMPLDtemplate = CMPLtemplate | 1 << 21;
    int mi = CMPLDtemplate | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitCRAND(CR BT, CR BA, CR BB) {
    final int CRANDtemplate = 19 << 26 | 257 << 1;
    int mi = CRANDtemplate | BT.value() << 21 | BA.value() << 16 | BB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitCRANDC(CR BT, CR BA, CR BB) {
    final int CRANDCtemplate = 19 << 26 | 129 << 1;
    int mi = CRANDCtemplate | BT.value() << 21 | BA.value() << 16 | BB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitCROR(CR BT, CR BA, CR BB) {
    final int CRORtemplate = 19 << 26 | 449 << 1;
    int mi = CRORtemplate | BT.value() << 21 | BA.value() << 16 | BB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitCRORC(CR BT, CR BA, CR BB) {
    final int CRORCtemplate = 19 << 26 | 417 << 1;
    int mi = CRORCtemplate | BT.value() << 21 | BA.value() << 16 | BB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitFADD(FPR FRT, FPR FRA, FPR FRB) {
    final int FADDtemplate = 63 << 26 | 21 << 1;
    int mi = FADDtemplate | FRT.value() << 21 | FRA.value() << 16 | FRB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitFADDS(FPR FRT, FPR FRA, FPR FRB) {
    final int FADDStemplate = 59 << 26 | 21 << 1; // single-percision add
    int mi = FADDStemplate | FRT.value() << 21 | FRA.value() << 16 | FRB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitFABS(FPR FRT, FPR FRB) {
    final int FABStemplate = 63 << 26 | 264 << 1;
    int mi = FABStemplate | FRT.value() << 21 | FRB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitFCMPU(FPR FRA, FPR FRB) {
    final int FCMPUtemplate = 63 << 26;
    int mi = FCMPUtemplate | FRA.value() << 16 | FRB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitFDIV(FPR FRT, FPR FRA, FPR FRB) {
    final int FDIVtemplate = 63 << 26 | 18 << 1;
    int mi = FDIVtemplate | FRT.value() << 21 | FRA.value() << 16 | FRB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitFDIVS(FPR FRT, FPR FRA, FPR FRB) {
    final int FDIVStemplate = 59 << 26 | 18 << 1; // single-precision divide
    int mi = FDIVStemplate | FRT.value() << 21 | FRA.value() << 16 | FRB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitFMUL(FPR FRT, FPR FRA, FPR FRB) {
    final int FMULtemplate = 63 << 26 | 25 << 1;
    int mi = FMULtemplate | FRT.value() << 21 | FRA.value() << 16 | FRB.value() << 6;
    
    appendInstruction(mi);
  }

  public final void emitFMULS(FPR FRT, FPR FRA, FPR FRB) {
    final int FMULStemplate = 59 << 26 | 25 << 1; // single-precision fm
    int mi = FMULStemplate | FRT.value() << 21 | FRA.value() << 16 | FRB.value() << 6;
    
    appendInstruction(mi);
  }

  public final void emitFMADD(FPR FRT, FPR FRA, FPR FRC, FPR FRB) {
    final int FMADDtemplate = 63 << 26 | 29 << 1;
    int mi = FMADDtemplate | FRT.value() << 21 | FRA.value() << 16 | FRB.value() << 11 | FRC.value() << 6;
    
    appendInstruction(mi);
  }

  public final void emitFNMSUB(FPR FRT, FPR FRA, FPR FRC, FPR FRB) {
    final int FNMSUBtemplate = 63 << 26 | 30 << 1;
    int mi = FNMSUBtemplate | FRT.value() << 21 | FRA.value() << 16 | FRB.value() << 11 | FRC.value() << 6;
    
    appendInstruction(mi);
  }

  public final void emitFNEG(FPR FRT, FPR FRB) {
    final int FNEGtemplate = 63 << 26 | 40 << 1;
    int mi = FNEGtemplate | FRT.value() << 21 | FRB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitFSQRT(FPR FRT, FPR FRB) {
    final int FSQRTtemplate = 63 << 26 | 22 << 1;
    int mi = FSQRTtemplate | FRT.value() << 21 | FRB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitFSQRTS(FPR FRT, FPR FRB) {
    final int FSQRTStemplate = 59 << 26 | 22 << 1;
    int mi = FSQRTStemplate | FRT.value() << 21 | FRB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitFSUB(FPR FRT, FPR FRA, FPR FRB) {
    final int FSUBtemplate = 63 << 26 | 20 << 1;
    int mi = FSUBtemplate | FRT.value() << 21 | FRA.value() << 16 | FRB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitFSUBS(FPR FRT, FPR FRA, FPR FRB) {
    final int FSUBStemplate = 59 << 26 | 20 << 1;
    int mi = FSUBStemplate | FRT.value() << 21 | FRA.value() << 16 | FRB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitFSEL(FPR FRT, FPR FRA, FPR FRC, FPR FRB) {
    final int FSELtemplate = 63 << 26 | 23 << 1;
    int mi = FSELtemplate | FRT.value() << 21 | FRA.value() << 16 | FRB.value() << 11 | FRC.value() << 6;
    
    appendInstruction(mi);
  }

  // LOAD/ STORE MULTIPLE

  // TODO!! verify that D is sign extended
  // (the Assembler Language Reference seems ambiguous)
  //

  public final void emitLMW(GPR RT, int D, GPR RA) {
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = (46 << 26) | RT.value() << 21 | RA.value() << 16 | (D & 0xFFFF);
    
    appendInstruction(mi);
  }

  // TODO!! verify that D is sign extended
  // (the Assembler Language Reference seems ambiguous)
  //
  public final void emitSTMW(GPR RT, int D, GPR RA) {
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = (47 << 26) | RT.value() << 21 | RA.value() << 16 | (D & 0xFFFF);
    
    appendInstruction(mi);
  }

  public final void emitLWZ(GPR RT, int D, GPR RA) {
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    _emitLWZ(RT, D & 0xFFFF, RA);
  }

  private void _emitLWZ(GPR RT, int D, GPR RA) {
    final int LWZtemplate = 32 << 26;
    //D has already been masked
    int mi = LWZtemplate | RT.value() << 21 | RA.value() << 16 | D;
    
    appendInstruction(mi);
  }

  public final void emitLBZ(GPR RT, int D, GPR RA) {
    final int LBZtemplate = 34 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = LBZtemplate | RT.value() << 21 | RA.value() << 16 | (D & 0xFFFF);
    
    appendInstruction(mi);
  }

  public final void emitLBZoffset(GPR RT, GPR RA, Offset D) {
    final int LBZtemplate = 34 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = LBZtemplate | RT.value() << 21 | RA.value() << 16 | maskLower16(D);
    
    appendInstruction(mi);
  }

  public final void emitLBZX(GPR RT, GPR RA, GPR RB) {
    final int LBZXtemplate = 31 << 26 | 87 << 1;
    int mi = LBZXtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitLHA(GPR RT, int D, GPR RA) {
    final int LHAtemplate = 42 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = LHAtemplate | RT.value() << 21 | RA.value() << 16 | (D & 0xFFFF);
    
    appendInstruction(mi);
  }

  public final void emitLHAoffset(GPR RT, GPR RA, Offset D) {
    final int LHAtemplate = 42 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = LHAtemplate | RT.value() << 21 | RA.value() << 16 | maskLower16(D);
    
    appendInstruction(mi);
  }

  public final void emitLHZ(GPR RT, int D, GPR RA) {
    final int LHZtemplate = 40 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = LHZtemplate | RT.value() << 21 | RA.value() << 16 | (D & 0xFFFF);
    
    appendInstruction(mi);
  }

  public final void emitLHZoffset(GPR RT, GPR RA, Offset D) {
    final int LHZtemplate = 40 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = LHZtemplate | RT.value() << 21 | RA.value() << 16 | maskLower16(D);
    
    appendInstruction(mi);
  }

  public final void emitLFD(FPR FRT, int D, GPR RA) {
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    _emitLFD(FRT, D & 0xFFFF, RA);
  }

  private void _emitLFD(FPR FRT, int D, GPR RA) {
    final int LFDtemplate = 50 << 26;
    //D has already been masked
    int mi = LFDtemplate | FRT.value() << 21 | RA.value() << 16 | D;
    
    appendInstruction(mi);
  }

  public final void emitLFDoffset(FPR FRT, GPR RA, Offset D) {
    final int LFDtemplate = 50 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = LFDtemplate | FRT.value() << 21 | RA.value() << 16 | maskLower16(D);
    
    appendInstruction(mi);
  }

  public final void emitLFDU(FPR FRT, int D, GPR RA) {
    final int LFDUtemplate = 51 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = LFDUtemplate | FRT.value() << 21 | RA.value() << 16 | (D & 0xFFFF);
    
    appendInstruction(mi);
  }

  public final void emitLFDX(FPR FRT, GPR RA, GPR RB) {
    final int LFDXtemplate = 31 << 26 | 599 << 1;
    int mi = LFDXtemplate | FRT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitLFS(FPR FRT, int D, GPR RA) {
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    _emitLFS(FRT, D & 0xFFFF, RA);
  }

  private void _emitLFS(FPR FRT, int D, GPR RA) {
    final int LFStemplate = 48 << 26;
    //D has already been masked
    int mi = LFStemplate | FRT.value() << 21 | RA.value() << 16 | D;
    
    appendInstruction(mi);
  }

  public static int LFSX(FPR FRT, GPR RA, GPR RB) {
    return 31 << 26 | FRT.value() << 21 | RA.value() << 16 | RB.value() << 11 | 535 << 1;
  }

  public final void emitLFSX(FPR FRT, GPR RA, GPR RB) {
    final int LFSXtemplate = 31 << 26 | 535 << 1;
    int mi = LFSXtemplate | FRT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitLHAX(GPR RT, GPR RA, GPR RB) {
    final int LHAXtemplate = 31 << 26 | 343 << 1;
    int mi = LHAXtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitLHZX(GPR RT, GPR RA, GPR RB) {
    final int LHZXtemplate = 31 << 26 | 279 << 1;
    int mi = LHZXtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  private void _emitLI(GPR RT, int D) {
    final int ADDItemplate = 14 << 26;
    //D has already been masked
    int mi = ADDItemplate | RT.value() << 21 | D;
    
    appendInstruction(mi);
  }

  public final void emitLWZU(GPR RT, int D, GPR RA) {
    final int LWZUtemplate = 33 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = LWZUtemplate | RT.value() << 21 | RA.value() << 16 | (D & 0xFFFF);
    
    appendInstruction(mi);
  }

  public final void emitLWZX(GPR RT, GPR RA, GPR RB) {
    final int LWZXtemplate = 31 << 26 | 23 << 1;
    int mi = LWZXtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitLWZUX(GPR RT, GPR RA, GPR RB) {
    final int LWZUXtemplate = 31 << 26 | 55 << 1;
    int mi = LWZUXtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitLWARX(GPR RT, GPR RA, GPR RB) {
    final int LWARXtemplate = 31 << 26 | 20 << 1;
    int mi = LWARXtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitMFLR(GPR RT) {
    final int MFLRtemplate = 31 << 26 | 0x08 << 16 | 339 << 1;
    int mi = MFLRtemplate | RT.value() << 21;
    
    appendInstruction(mi);
  }

  final void emitMFSPR(GPR RT, int SPR) {
    final int MFSPRtemplate = 31 << 26 | 339 << 1;
    int mi = MFSPRtemplate | RT.value() << 21 | SPR << 16;
    
    appendInstruction(mi);
  }

  public final void emitMTLR(GPR RS) {
    final int MTLRtemplate = 31 << 26 | 0x08 << 16 | 467 << 1;
    int mi = MTLRtemplate | RS.value() << 21;
    
    appendInstruction(mi);
  }

  public final void emitMTCTR(GPR RS) {
    final int MTCTRtemplate = 31 << 26 | 0x09 << 16 | 467 << 1;
    int mi = MTCTRtemplate | RS.value() << 21;
    
    appendInstruction(mi);
  }

  public final void emitFMR(FPR RA, FPR RB) {
    final int FMRtemplate = 63 << 26 | 72 << 1;
    int mi = FMRtemplate | RA.value() << 21 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitFRSP(FPR RT, FPR RB) {
    final int FRSPtemplate = 63 << 26 | 12 << 1;
    int mi = FRSPtemplate | RT.value() << 21 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitMULHWU(GPR RT, GPR RA, GPR RB) {
    final int MULHWUtemplate = 31 << 26 | 11 << 1;
    int mi = MULHWUtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitDIVW(GPR RT, GPR RA, GPR RB) {
    final int DIVWtemplate = 31 << 26 | 491 << 1;
    int mi = DIVWtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitMULLW(GPR RT, GPR RA, GPR RB) {
    final int MULLWtemplate = 31 << 26 | 235 << 1;
    int mi = MULLWtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitNEG(GPR RT, GPR RA) {
    final int NEGtemplate = 31 << 26 | 104 << 1;
    int mi = NEGtemplate | RT.value() << 21 | RA.value() << 16;
    
    appendInstruction(mi);
  }

  public final void emitOR(GPR RA, GPR RS, GPR RB) {
    final int ORtemplate = 31 << 26 | 444 << 1;
    int mi = ORtemplate | RS.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  // move register RT <- RS
  public final void emitMR(GPR RT, GPR RS) {
    emitOR(RT, RS, RS);
  }

  public final void emitORI(GPR RA, GPR RS, int UI) {
    final int ORItemplate = 24 << 26;
    if (VM.VerifyAssertions) VM._assert(UI == (UI & 0xFFFF));
    int mi = ORItemplate | RS.value() << 21 | RA.value() << 16 | (UI & 0xFFFF);
    
    appendInstruction(mi);
  }

  public final void emitORIS(GPR RA, GPR RS, int UI) {
    final int ORIStemplate = 25 << 26;
    if (VM.VerifyAssertions) VM._assert(UI == (UI & 0xFFFF));
    int mi = ORIStemplate | RS.value() << 21 | RA.value() << 16 | (UI & 0xFFFF);
    
    appendInstruction(mi);
  }

  public final void emitRLWINM(GPR RA, GPR RS, int SH, int MB, int ME) {
    final int RLWINM_template = 21 << 26;
    int mi = RLWINM_template | RS.value() << 21 | RA.value() << 16 | SH << 11 | MB << 6 | ME << 1;
    
    appendInstruction(mi);
  }

  public final void emitSUBFCr(GPR RT, GPR RA, GPR RB) {
    final int SUBFCrtemplate = 31 << 26 | 8 << 1 | 1;
    int mi = SUBFCrtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitSUBFC(GPR RT, GPR RA, GPR RB) {
    final int SUBFCtemplate = 31 << 26 | 8 << 1;
    int mi = SUBFCtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitSUBFIC(GPR RA, GPR RS, int S) {
    final int SUBFICtemplate = 8 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(S, 16));
    int mi = SUBFICtemplate | RS.value() << 21 | RA.value() << 16 | S;
    
    appendInstruction(mi);
  }

  public final void emitSUBFEr(GPR RT, GPR RA, GPR RB) {
    final int SUBFErtemplate = 31 << 26 | 136 << 1 | 1;
    int mi = SUBFErtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitSUBFE(GPR RT, GPR RA, GPR RB) {
    final int SUBFEtemplate = 31 << 26 | 136 << 1;
    int mi = SUBFEtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitSUBFZE(GPR RT, GPR RA) {
    final int SUBFZEtemplate = 31 << 26 | 200 << 1;
    int mi = SUBFZEtemplate | RT.value() << 21 | RA.value() << 16;
    
    appendInstruction(mi);
  }

  public final void emitSLW(GPR RA, GPR RS, GPR RB) {
    final int SLWtemplate = 31 << 26 | 24 << 1;
    int mi = SLWtemplate | RS.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitSLWI(GPR RA, GPR RS, int N) {
    final int SLWItemplate = 21 << 26;
    int mi = SLWItemplate | RS.value() << 21 | RA.value() << 16 | N << 11 | (31 - N) << 1;
    
    appendInstruction(mi);
  }

  public final void emitSRW(GPR RA, GPR RS, GPR RB) {
    final int SRWtemplate = 31 << 26 | 536 << 1;
    int mi = SRWtemplate | RS.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitSRAW(GPR RA, GPR RS, GPR RB) {
    final int SRAWtemplate = 31 << 26 | 792 << 1;
    int mi = SRAWtemplate | RS.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitSRAWI(GPR RA, GPR RS, int SH) {
    final int SRAWItemplate = 31 << 26 | 824 << 1;
    int mi = SRAWItemplate | RS.value() << 21 | RA.value() << 16 | SH << 11;
    
    appendInstruction(mi);
  }

  public final void emitSRAWIr(GPR RA, GPR RS, int SH) {
    final int SRAWIrtemplate = 31 << 26 | 824 << 1 | 1;
    int mi = SRAWIrtemplate | RS.value() << 21 | RA.value() << 16 | SH << 11;
    
    appendInstruction(mi);
  }

  public final void emitSTW(GPR RS, int D, GPR RA) {
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    _emitSTW(RS, D & 0xFFFF, RA);
  }

  private void _emitSTW(GPR RS, int D, GPR RA) {
    final int STWtemplate = 36 << 26;
    //D has already been masked
    int mi = STWtemplate | RS.value() << 21 | RA.value() << 16 | D;
    
    appendInstruction(mi);
  }

  public final void emitSTWoffset(GPR RS, GPR RA, Offset D) {
    final int STWtemplate = 36 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = STWtemplate | RS.value() << 21 | RA.value() << 16 | maskLower16(D);
    
    appendInstruction(mi);
  }

  public final void emitSTB(GPR RS, int D, GPR RA) {
    final int STBtemplate = 38 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = STBtemplate | RS.value() << 21 | RA.value() << 16 | (D & 0xFFFF);
    
    appendInstruction(mi);
  }

  public final void emitSTBoffset(GPR RS, GPR RA, Offset D) {
    final int STBtemplate = 38 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = STBtemplate | RS.value() << 21 | RA.value() << 16 | maskLower16(D);
    
    appendInstruction(mi);
  }

  public final void emitSTH(GPR RS, int D, GPR RA) {
    final int STHtemplate = 44 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = STHtemplate | RS.value() << 21 | RA.value() << 16 | (D & 0xFFFF);
    
    appendInstruction(mi);
  }

  public final void emitSTHoffset(GPR RS, GPR RA, Offset D) {
    final int STHtemplate = 44 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = STHtemplate | RS.value() << 21 | RA.value() << 16 | maskLower16(D);
    
    appendInstruction(mi);
  }

  public final void emitSTBX(GPR RS, GPR RA, GPR RB) {
    final int STBXtemplate = 31 << 26 | 215 << 1;
    int mi = STBXtemplate | RS.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitSTHX(GPR RS, GPR RA, GPR RB) {
    final int STHXtemplate = 31 << 26 | 407 << 1;
    int mi = STHXtemplate | RS.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitSTWX(GPR RS, GPR RA, GPR RB) {
    final int STWXtemplate = 31 << 26 | 151 << 1;
    int mi = STWXtemplate | RS.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitSTFSX(FPR FRS, GPR RA, GPR RB) {
    final int STFSXtemplate = 31 << 26 | 663 << 1;
    int mi = STFSXtemplate | FRS.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitSTFD(FPR FRS, int D, GPR RA) {
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    _emitSTFD(FRS, D & 0xFFFF, RA);
  }

  private void _emitSTFD(FPR FRS, int D, GPR RA) {
    final int STFDtemplate = 54 << 26;
    //D has already been masked
    int mi = STFDtemplate | FRS.value() << 21 | RA.value() << 16 | D;
    
    appendInstruction(mi);
  }

  public final void emitSTFDoffset(FPR FRS, GPR RA, Offset D) {
    final int STFDtemplate = 54 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = STFDtemplate | FRS.value() << 21 | RA.value() << 16 | maskLower16(D);
    
    appendInstruction(mi);
  }

  public final void emitSTFDU(FPR FRS, int D, GPR RA) {
    final int STFDUtemplate = 55 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = STFDUtemplate | FRS.value() << 21 | RA.value() << 16 | (D & 0xFFFF);
    
    appendInstruction(mi);
  }

  public final void emitSTFDX(FPR FRS, GPR RA, GPR RB) {
    final int STFDXtemplate = 31 << 26 | 727 << 1;
    int mi = STFDXtemplate | FRS.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitSTFS(FPR FRS, int D, GPR RA) {
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    _emitSTFS(FRS, D & 0xFFFF, RA);
  }

  private void _emitSTFS(FPR FRS, int D, GPR RA) {
    final int STFStemplate = 52 << 26;
    //D has already been masked
    int mi = STFStemplate | FRS.value() << 21 | RA.value() << 16 | D;
    
    appendInstruction(mi);
  }

  public final void emitSTFSoffset(FPR FRS, GPR RA, Offset D) {
    final int STFStemplate = 52 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = STFStemplate | FRS.value() << 21 | RA.value() << 16 | maskLower16(D);
    
    appendInstruction(mi);
  }

  public final void emitSTFSU(FPR FRS, int D, GPR RA) {
    final int STFSUtemplate = 53 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = STFSUtemplate | FRS.value() << 21 | RA.value() << 16 | (D & 0xFFFF);
    
    appendInstruction(mi);
  }

  public final void emitSTWU(GPR RS, int D, GPR RA) {
    final int STWUtemplate = 37 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(D, 16));
    int mi = STWUtemplate | RS.value() << 21 | RA.value() << 16 | (D & 0xFFFF);
    
    appendInstruction(mi);
  }

  public final void emitSTWUX(GPR RS, GPR RA, GPR RB) {
    final int STWUXtemplate = 31 << 26 | 183 << 1;
    int mi = STWUXtemplate | RS.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitSTWCXr(GPR RS, GPR RA, GPR RB) {
    final int STWCXrtemplate = 31 << 26 | 150 << 1 | 1;
    int mi = STWCXrtemplate | RS.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitTWLE(GPR RA, GPR RB) {
    final int TWtemplate = 31 << 26 | 4 << 1;
    final int TWLEtemplate = TWtemplate | 0x14 << 21;
    int mi = TWLEtemplate | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitTWLT(GPR RA, GPR RB) {
    final int TWtemplate = 31 << 26 | 4 << 1;
    final int TWLTtemplate = TWtemplate | 0x10 << 21;
    int mi = TWLTtemplate | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitTWNE(GPR RA, GPR RB) {
    final int TWtemplate = 31 << 26 | 4 << 1;
    final int TWNEtemplate = TWtemplate | 0x18 << 21;
    int mi = TWNEtemplate | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitTWLLE(GPR RA, GPR RB) {
    final int TWtemplate = 31 << 26 | 4 << 1;
    final int TWLLEtemplate = TWtemplate | 0x6 << 21;
    int mi = TWLLEtemplate | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitTWI(int TO, GPR RA, int SI) {
    final int TWItemplate = 3 << 26;
    int mi = TWItemplate | TO << 21 | RA.value() << 16 | SI & 0xFFFF;
    
    appendInstruction(mi);
  }

  public final void emitTWEQ0(GPR RA) {
    final int TWItemplate = 3 << 26;
    final int TWEQItemplate = TWItemplate | 0x4 << 21;
    int mi = TWEQItemplate | RA.value() << 16;
    
    appendInstruction(mi);
  }

  public final void emitTWWI(int imm) {
    final int TWItemplate = 3 << 26;
    final int TWWItemplate = TWItemplate | 0x3EC << 16;      // RA == 12
    int mi = TWWItemplate | imm;
    
    appendInstruction(mi);
  }

  public final void emitXOR(GPR RA, GPR RS, GPR RB) {
    final int XORtemplate = 31 << 26 | 316 << 1;
    int mi = XORtemplate | RS.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitXORI(GPR RA, GPR RS, int V) {
    final int XORItemplate = 26 << 26;
    if (VM.VerifyAssertions) VM._assert(fits(V, 16));
    int mi = XORItemplate | RS.value() << 21 | RA.value() << 16 | V & 0xFFFF;
    
    appendInstruction(mi);
  }

  /* macro instructions */

  public final void emitNOP() {
    int mi = 24 << 26; //ORI 0,0,0
    
    appendInstruction(mi);
  }

  //private: use emitLIntOffset or emitLAddrOffset instead
  private void emitLDoffset(GPR RT, GPR RA, Offset offset) {
    if (fits(offset, 16)) {
      _emitLD(RT, maskLower16(offset), RA);
    } else {
      if (VM.VerifyAssertions) VM._assert(fits(offset, 32));
      _emitADDIS(RT, RA, maskUpper16(offset));
      _emitLD(RT, maskLower16(offset), RT);
    }
  }

  //private: use emitLIntOffset or emitLAddrOffset instead
  private void emitLWAoffset(GPR RT, GPR RA, Offset offset) {
    if (fits(offset, 16)) {
      _emitLWA(RT, maskLower16(offset), RA);
    } else {
      if (VM.VerifyAssertions) VM._assert(fits(offset, 32));
      _emitADDIS(RT, RA, maskUpper16(offset));
      _emitLWA(RT, maskLower16(offset), RT);
    }
  }

  //private: use emitLIntOffset or emitLAddrOffset instead
  private void emitLWZoffset(GPR RT, GPR RA, Offset offset) {
    if (fits(offset, 16)) {
      _emitLWZ(RT, maskLower16(offset), RA);
    } else {
      if (VM.VerifyAssertions) VM._assert(fits(offset, 32));
      _emitADDIS(RT, RA, maskUpper16(offset));
      _emitLWZ(RT, maskLower16(offset), RT);
    }
  }

  public final void emitSTDtoc(GPR RT, Offset offset, GPR Rz) {
    if (fits(offset, 16)) {
      _emitSTD(RT, maskLower16(offset), JTOC);
    } else {
      if (VM.VerifyAssertions) VM._assert(fits(offset, 32));
      _emitADDIS(Rz, JTOC, maskUpper16(offset));
      _emitSTD(RT, maskLower16(offset), Rz);
    }
  }

  public final void emitSTWtoc(GPR RT, Offset offset, GPR Rz) {
    if (fits(offset, 16)) {
      _emitSTW(RT, maskLower16(offset), JTOC);
    } else {
      if (VM.VerifyAssertions) VM._assert(fits(offset, 32));
      _emitADDIS(Rz, JTOC, maskUpper16(offset));
      _emitSTW(RT, maskLower16(offset), Rz);
    }
  }

  public final void emitLFDtoc(FPR FRT, Offset offset, GPR Rz) {
    if (fits(offset, 16)) {
      _emitLFD(FRT, maskLower16(offset), JTOC);
    } else {
      if (VM.VerifyAssertions) VM._assert(fits(offset, 32));
      _emitADDIS(Rz, JTOC, maskUpper16(offset));
      _emitLFD(FRT, maskLower16(offset), Rz);
    }
  }

  public final void emitSTFDtoc(FPR FRT, Offset offset, GPR Rz) {
    if (fits(offset, 16)) {
      _emitSTFD(FRT, maskLower16(offset), JTOC);
    } else {
      if (VM.VerifyAssertions) VM._assert(fits(offset, 32));
      _emitADDIS(Rz, JTOC, maskUpper16(offset));
      _emitSTFD(FRT, maskLower16(offset), Rz);
    }
  }

  public final void emitLFStoc(FPR FRT, Offset offset, GPR Rz) {
    if (fits(offset, 16)) {
      _emitLFS(FRT, maskLower16(offset), JTOC);
    } else {
      if (VM.VerifyAssertions) VM._assert(fits(offset, 32));
      _emitADDIS(Rz, JTOC, maskUpper16(offset));
      _emitLFS(FRT, maskLower16(offset), Rz);
    }
  }

  public final void emitSTFStoc(FPR FRT, Offset offset, GPR Rz) {
    if (fits(offset, 16)) {
      _emitSTFS(FRT, maskLower16(offset), JTOC);
    } else {
      if (VM.VerifyAssertions) VM._assert(fits(offset, 32));
      _emitADDIS(Rz, JTOC, maskUpper16(offset));
      _emitSTFS(FRT, maskLower16(offset), Rz);
    }
  }

  public final void emitLVALAddr(GPR RT, Offset off) {
    emitLVALAddr(RT, off.toWord().toAddress());
  }

  public final void emitLVALAddr(GPR RT, Address addr) {
    Offset val = addr.toWord().toOffset();
    if (VM.BuildFor64Addr) {
      if (!fits(val, 48)) {
        val = val.toWord().lsh(32).rsha(32).toOffset();
        Offset valHigh = addr.minus(val).toWord().rsha(32).toOffset();
        _emitADDIS(RT, maskUpper16(valHigh));
        _emitADDI(RT, maskLower16(valHigh), RT);
        emitSLDI(RT, RT, 32);
        _emitADDIS(RT, RT, maskUpper16(val));
        _emitADDI(RT, maskLower16(val), RT);
      } else if (!fits(val, 32)) {
        val = val.toWord().lsh(32).rsha(32).toOffset();
        Offset valHigh = addr.minus(val).toWord().rsha(32).toOffset();
        _emitLI(RT, maskLower16(valHigh));
        emitSLDI(RT, RT, 32);
        _emitADDIS(RT, RT, maskUpper16(val));
        _emitADDI(RT, maskLower16(val), RT);
      } else if (!fits(val, 16)) {
        _emitADDIS(RT, maskUpper16(val));
        _emitADDI(RT, maskLower16(val), RT);
      } else {
        _emitLI(RT, maskLower16(val));
      }
    } else {
      if (!fits(val, 16)) {
        _emitADDIS(RT, maskUpper16(val));
        _emitADDI(RT, maskLower16(val), RT);
      } else {
        _emitLI(RT, maskLower16(val));
      }
    }
  }

  public final void emitLVAL(GPR RT, int val) {
    if (fits(val, 16)) {
      _emitLI(RT, val & 0xFFFF);
    } else {
      _emitADDIS(RT, val >>> 16);
      emitORI(RT, RT, val & 0xFFFF);
    }
  }


  public void disassemble(int start, int stop) {
    for (int i = start; i < stop; i++) {
      VM.sysWrite(Services.getHexString(i << LG_INSTRUCTION_WIDTH, true));
      VM.sysWrite(" : ");
      VM.sysWrite(Services.getHexString(machineCodes[i], false));
      VM.sysWrite("  ");
      VM.sysWrite(Disassembler.disasm(machineCodes[i], i << LG_INSTRUCTION_WIDTH));
      VM.sysWrite("\n");
    }
  }

  /**
   * Append a CodeArray to the current machine code
   */
  public void appendInstructions(CodeArray instructionSegment) {
    for (int i = 0; i < instructionSegment.length(); i++) {      
      appendInstruction(instructionSegment.get(i));
    }
  }

  // new PowerPC instructions

  /**
   * The "sync" on Power 4 architectures are expensive and so we use "lwsync" instead to
   * implement SYNC.  On older arhictectures, there is no problem but the weaker semantics
   * of lwsync means that there are memory consistency bugs we might need to flush out.
   */
  public final void emitSYNC() {
    //final int SYNCtemplate = 31<<26 | 598<<1;
    final int LWSYNCtemplate = 31<<26 | 1 << 21 | 598<<1;
    int mi = LWSYNCtemplate;
    
    appendInstruction(mi);
  }

  public final void emitICBI(GPR RA, GPR RB) {
    final int ICBItemplate = 31 << 26 | 982 << 1;
    int mi = ICBItemplate | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitISYNC() {
    final int ISYNCtemplate = 19 << 26 | 150 << 1;
    int mi = ISYNCtemplate;
    
    appendInstruction(mi);
  }

  public final void emitDCBF(GPR RA, GPR RB) {
    final int DCBFtemplate = 31 << 26 | 86 << 1;
    int mi = DCBFtemplate | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitDCBST(GPR RA, GPR RB) {
    final int DCBSTtemplate = 31 << 26 | 54 << 1;
    int mi = DCBSTtemplate | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitDCBT(GPR RA, GPR RB) {
    final int DCBTtemplate = 31 << 26 | 278 << 1;
    int mi = DCBTtemplate | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitDCBTST(GPR RA, GPR RB) {
    final int DCBTSTtemplate = 31 << 26 | 246 << 1;
    int mi = DCBTSTtemplate | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitDCBZ(GPR RA, GPR RB) {
    final int DCBZtemplate = 31 << 26 | 1014 << 1;
    int mi = DCBZtemplate | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitDCBZL(GPR RA, GPR RB) {
    final int DCBZLtemplate = 31 << 26 | 1 << 21 | 1014 << 1;
    int mi = DCBZLtemplate | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitMFTB(GPR RT) {
    final int MFTBtemplate = 31 << 26 | 392 << 11 | 371 << 1;
    int mi = MFTBtemplate | RT.value() << 21;
    
    appendInstruction(mi);
  }

  public final void emitMFTBU(GPR RT) {
    final int MFTBUtemplate = 31 << 26 | 424 << 11 | 371 << 1;
    int mi = MFTBUtemplate | RT.value() << 21;
    
    appendInstruction(mi);
  }

  public final void emitFCTIWZ(FPR FRT, FPR FRB) {
    final int FCTIWZtemplate = 63 << 26 | 15 << 1;
    int mi = FCTIWZtemplate | FRT.value() << 21 | FRB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitEXTSB(GPR RA, GPR RS) {
    final int EXTSBtemplate = 31 << 26 | 954 << 1;
    int mi = EXTSBtemplate | RS.value() << 21 | RA.value() << 16;
    
    appendInstruction(mi);
  }

  // PowerPC 64-bit instuctions

  public final void emitDIVD(GPR RT, GPR RA, GPR RB) {
    final int DIVDtemplate = 31 << 26 | 489 << 1;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = DIVDtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }


  public final void emitEXTSW(GPR RA, GPR RS) {
    final int EXTSWtemplate = 31 << 26 | 986 << 1;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = EXTSWtemplate | RS.value() << 21 | RA.value() << 16;
    
    appendInstruction(mi);
  }

  public final void emitFCTIDZ(FPR FRT, FPR FRB) {
    final int FCTIDZtemplate = 63 << 26 | 815 << 1;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = FCTIDZtemplate | FRT.value() << 21 | FRB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitFCFID(FPR FRT, FPR FRB) {
    final int FCFIDtemplate = 63 << 26 | 846 << 1;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = FCFIDtemplate | FRT.value() << 21 | FRB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitLD(GPR RT, int DS, GPR RA) {
    if (VM.VerifyAssertions) VM._assert(fits(DS, 16));
    _emitLD(RT, DS, RA);
  }

  private void _emitLD(GPR RT, int DS, GPR RA) {
    final int LDtemplate = 58 << 26;
    //DS is already checked to fit 16 bits
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = LDtemplate | RT.value() << 21 | RA.value() << 16 | (DS & 0xFFFC);
    
    appendInstruction(mi);
  }

  public final void emitLDARX(GPR RT, GPR RA, GPR RB) {
    final int LDARXtemplate = 31 << 26 | 84 << 1;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = LDARXtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitLDU(GPR RT, int DS, GPR RA) {
    final int LDUtemplate = 58 << 26 | 1;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    if (VM.VerifyAssertions) VM._assert(fits(DS, 16));
    int mi = LDUtemplate | RT.value() << 21 | RA.value() << 16 | (DS & 0xFFFC);
    
    appendInstruction(mi);
  }

  public final void emitLDUX(GPR RT, GPR RA, GPR RB) {
    final int LDUXtemplate = 31 << 26 | 53 << 1;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = LDUXtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitLDX(GPR RT, GPR RA, GPR RB) {
    final int LDXtemplate = 31 << 26 | 21 << 1;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = LDXtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitLWA(GPR RT, int DS, GPR RA) {
    if (VM.VerifyAssertions) VM._assert(fits(DS, 16));
    _emitLWA(RT, DS, RA);
  }

  private void _emitLWA(GPR RT, int DS, GPR RA) {
    final int LWAtemplate = 58 << 26 | 2;
    //DS is already checked to fit 16 bits
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = LWAtemplate | RT.value() << 21 | RA.value() << 16 | (DS & 0xFFFC);
    
    appendInstruction(mi);
  }

  public final void emitLWAX(GPR RT, GPR RA, GPR RB) {
    final int LWAXtemplate = 31 << 26 | 341 << 1;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = LWAXtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitMULHDU(GPR RT, GPR RA, GPR RB) {
    final int MULHDUtemplate = 31 << 26 | 9 << 1;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = MULHDUtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitMULLD(GPR RT, GPR RA, GPR RB) {
    final int MULLDtemplate = 31 << 26 | 233 << 1;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = MULLDtemplate | RT.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitSLDI(GPR RA, GPR RS, int N) {
    final int SLDItemplate = 30 << 26 | 1 << 2;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi =
        SLDItemplate |
        RS.value() << 21 |
        RA.value() << 16 |
        (N & 0x1F) << 11 |
        ((63 - N) & 0x1F) << 6 |
        ((63 - N) & 0x20) |
        (N & 0x20) >> 4;
    
    appendInstruction(mi);
  }

  public final void emitRLDINM(GPR RA, GPR RS, int SH, int MB, int ME) {
    VM._assert(false, "PLEASE IMPLEMENT ME");
  }

  public final void emitSLD(GPR RA, GPR RS, GPR RB) {
    final int SLDtemplate = 31 << 26 | 27 << 1;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = SLDtemplate | RS.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitSRAD(GPR RA, GPR RS, GPR RB) {
    final int SRADtemplate = 31 << 26 | 794 << 1;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = SRADtemplate | RS.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitSRADI(GPR RA, GPR RS, int SH) {
    final int SRADItemplate = 31 << 26 | 413 << 2;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = SRADItemplate | RS.value() << 21 | RA.value() << 16 | (SH & 0x1F) << 11 | (SH & 0x20) >> 4;
    
    appendInstruction(mi);
  }

  public final void emitSRADIr(GPR RA, GPR RS, int SH) {
    final int SRADItemplate = 31 << 26 | 413 << 2;
    final int SRADIrtemplate = SRADItemplate | 1;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = SRADIrtemplate | RS.value() << 21 | RA.value() << 16 | (SH & 0x1F) << 11 | (SH & 0x20) >> 4;
    
    appendInstruction(mi);
  }

  public final void emitSRD(GPR RA, GPR RS, GPR RB) {
    final int SRDtemplate = 31 << 26 | 539 << 1;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = SRDtemplate | RS.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitSTD(GPR RS, int DS, GPR RA) {
    if (VM.VerifyAssertions) VM._assert(fits(DS, 16));
    _emitSTD(RS, DS, RA);
  }

  private void _emitSTD(GPR RS, int DS, GPR RA) {
    final int STDtemplate = 62 << 26;
    //DS is already checked to fit 16 bits
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = STDtemplate | RS.value() << 21 | RA.value() << 16 | (DS & 0xFFFC);
    
    appendInstruction(mi);
  }

  //private: use emitSTWOffset or emitSTAddrOffset instead
  private void emitSTDoffset(GPR RS, GPR RA, Offset Dis) {
    final int STDtemplate = 62 << 26;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    if (VM.VerifyAssertions) VM._assert(fits(Dis, 16));
    int DS = maskLower16(Dis);
    int mi = STDtemplate | RS.value() << 21 | RA.value() << 16 | (DS & 0xFFFC);
    
    appendInstruction(mi);
  }

  public final void emitSTDCXr(GPR RS, GPR RA, GPR RB) {
    final int STDCXrtemplate = 31 << 26 | 214 << 1 | 1;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = STDCXrtemplate | RS.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitSTDU(GPR RS, int DS, GPR RA) {
    final int STDUtemplate = 62 << 26 | 1;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    if (VM.VerifyAssertions) VM._assert(fits(DS, 16));
    int mi = STDUtemplate | RS.value() << 21 | RA.value() << 16 | (DS & 0xFFFC);
    
    appendInstruction(mi);
  }

  public final void emitSTDUX(GPR RS, GPR RA, GPR RB) {
    final int STDUXtemplate = 31 << 26 | 181 << 1;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = STDUXtemplate | RS.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitSTDX(GPR RS, GPR RA, GPR RB) {
    final int STDXtemplate = 31 << 26 | 149 << 1;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = STDXtemplate | RS.value() << 21 | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitTDLE(GPR RA, GPR RB) {
    final int TDtemplate = 31 << 26 | 68 << 1;
    final int TDLEtemplate = TDtemplate | 0x14 << 21;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = TDLEtemplate | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitTDLT(GPR RA, GPR RB) {
    final int TDtemplate = 31 << 26 | 68 << 1;
    final int TDLTtemplate = TDtemplate | 0x10 << 21;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = TDLTtemplate | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitTDLLE(GPR RA, GPR RB) {
    final int TDtemplate = 31 << 26 | 68 << 1;
    final int TDLLEtemplate = TDtemplate | 0x6 << 21;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = TDLLEtemplate | RA.value() << 16 | RB.value() << 11;
    
    appendInstruction(mi);
  }

  public final void emitTDI(int TO, GPR RA, int SI) {
    final int TDItemplate = 2 << 26;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = TDItemplate | TO << 21 | RA.value() << 16 | SI & 0xFFFF;
    
    appendInstruction(mi);
  }

  public final void emitTDEQ0(GPR RA) {
    final int TDItemplate = 2 << 26;
    final int TDEQItemplate = TDItemplate | 0x4 << 21;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = TDEQItemplate | RA.value() << 16;
    
    appendInstruction(mi);
  }

  public final void emitTDWI(int SI) {
    final int TDItemplate = 2 << 26;
    final int TDWItemplate = TDItemplate | 0x1F << 21 | 0xC << 16;
    if (!VM.BuildFor64Addr && VM.VerifyAssertions) VM._assert(false);
    int mi = TDWItemplate | SI & 0xFFFF;
    
    appendInstruction(mi);
  }

  // -------------------------------------------------------------- //
  // The following section contains macros to handle address values //
  // -------------------------------------------------------------- //
  public final void emitCMPLAddr(GPR reg1, GPR reg2) {
    if (VM.BuildFor64Addr) {
      emitCMPLD(reg1, reg2);
    } else {
      emitCMPL(reg1, reg2);
    }
  }

  public final void emitCMPAddr(GPR reg1, GPR reg2) {

    if (VM.BuildFor64Addr) {
      emitCMPD(reg1, reg2);
    } else {
      emitCMP(reg1, reg2);
    }
  }

  public final void emitCMPAddrI(GPR RA, int V) {
    if (VM.BuildFor64Addr) {
      emitCMPDI(RA, V);
    } else {
      emitCMPI(RA, V);
    }
  }

  public final void emitSRAddr(GPR RA, GPR RS, GPR RB) {
    if (VM.BuildFor64Addr) {
      emitSRD(RA, RS, RB);
    } else {
      emitSRW(RA, RS, RB);
    }
  }

  public final void emitSRA_Addr(GPR RA, GPR RS, GPR RB) {
    if (VM.BuildFor64Addr) {
      emitSRAD(RA, RS, RB);
    } else {
      emitSRAW(RA, RS, RB);
    }
  }

  public final void emitSRA_AddrI(GPR RA, GPR RS, int SH) {
    if (VM.BuildFor64Addr) {
      emitSRADI(RA, RS, SH);
    } else {
      emitSRAWI(RA, RS, SH);
    }
  }

  public final void emitSLAddr(GPR RA, GPR RS, GPR RB) {
    if (VM.BuildFor64Addr) {
      emitSLD(RA, RS, RB);
    } else {
      emitSLW(RA, RS, RB);
    }
  }

  public final void emitSLAddrI(GPR RA, GPR RS, int N) {
    if (VM.BuildFor64Addr) {
      emitSLDI(RA, RS, N);
    } else {
      emitSLWI(RA, RS, N);
    }
  }

  public final void emitTAddrLT(GPR RA, GPR RB) {
    if (VM.BuildFor64Addr) {
      emitTDLT(RA, RB);
    } else {
      emitTWLT(RA, RB);
    }
  }

  public final void emitTAddrLE(GPR RA, GPR RB) {
    if (VM.BuildFor64Addr) {
      emitTDLE(RA, RB);
    } else {
      emitTWLE(RA, RB);
    }
  }

  public final void emitTAddrLLE(GPR RA, GPR RB) {
    if (VM.BuildFor64Addr) {
      emitTDLLE(RA, RB);
    } else {
      emitTWLLE(RA, RB);
    }
  }

  public final void emitTAddrI(int TO, GPR RA, int SI) {
    if (VM.BuildFor64Addr) {
      emitTDI(TO, RA, SI);
    } else {
      emitTWI(TO, RA, SI);
    }
  }

  public final void emitTAddrEQ0(GPR RA) {
    if (VM.BuildFor64Addr) {
      emitTDEQ0(RA);
    } else {
      emitTWEQ0(RA);
    }
  }

  public final void emitTAddrWI(int SI) {
    if (VM.BuildFor64Addr) {
      emitTDWI(SI);
    } else {
      emitTWWI(SI);
    }
  }

  public final void emitSTAddr(GPR src_reg, int offset, GPR dest_reg) {

    if (VM.BuildFor64Addr) {
      emitSTD(src_reg, offset, dest_reg);
    } else {
      emitSTW(src_reg, offset, dest_reg);
    }
  }

  public final void emitSTAddrOffset(GPR src_reg, GPR dest_reg, Offset offset) {

    if (VM.BuildFor64Addr) {
      emitSTDoffset(src_reg, dest_reg, offset);
    } else {
      emitSTWoffset(src_reg, dest_reg, offset);
    }
  }

  public final void emitSTAddrX(GPR src_reg, GPR offset_reg, GPR dest_reg) {

    if (VM.BuildFor64Addr) {
      emitSTDX(src_reg, offset_reg, dest_reg);
    } else {
      emitSTWX(src_reg, offset_reg, dest_reg);
    }
  }

  public final void emitSTAddrU(GPR src_reg, int offset, GPR dest_reg) {

    if (VM.BuildFor64Addr) {
      emitSTDU(src_reg, offset, dest_reg);
    } else {
      emitSTWU(src_reg, offset, dest_reg);
    }
  }

  public final void emitLAddr(GPR dest_reg, int offset, GPR src_reg) {

    if (VM.BuildFor64Addr) {
      emitLD(dest_reg, offset, src_reg);
    } else {
      emitLWZ(dest_reg, offset, src_reg);
    }
  }

  public final void emitLAddrX(GPR dest_reg, GPR offset_reg, GPR src_reg) {

    if (VM.BuildFor64Addr) {
      emitLDX(dest_reg, offset_reg, src_reg);
    } else {
      emitLWZX(dest_reg, offset_reg, src_reg);
    }
  }

  public final void emitLAddrU(GPR dest_reg, int offset, GPR src_reg) {

    if (VM.BuildFor64Addr) {
      emitLDU(dest_reg, offset, src_reg);
    } else {
      emitLWZU(dest_reg, offset, src_reg);
    }
  }

  public final void emitLAddrToc(GPR dest_reg, Offset TOCoffset) {
    if (VM.BuildFor64Addr) {
      emitLDoffset(dest_reg, JTOC, TOCoffset);
    } else {
      emitLWZoffset(dest_reg, JTOC, TOCoffset);
    }
  }

  final void emitRLAddrINM(GPR RA, GPR RS, int SH, int MB, int ME) {

    if (VM.BuildFor64Addr) {
      emitRLDINM(RA, RS, SH, MB, ME);
    } else {
      emitRLWINM(RA, RS, SH, MB, ME);
    }
  }

  public final void emitLInt(GPR dest_reg, int offset, GPR src_reg) {

    if (VM.BuildFor64Addr) {
      emitLWA(dest_reg, offset, src_reg);
    } else {
      emitLWZ(dest_reg, offset, src_reg);
    }
  }

  public final void emitLIntX(GPR dest_reg, GPR offset_reg, GPR src_reg) {

    if (VM.BuildFor64Addr) {
      emitLWAX(dest_reg, offset_reg, src_reg);
    } else {
      emitLWZX(dest_reg, offset_reg, src_reg);
    }
  }

  public final void emitLIntToc(GPR dest_reg, Offset TOCoffset) {
    if (VM.BuildFor64Addr) {
      emitLWAoffset(dest_reg, JTOC, TOCoffset);
    } else {
      emitLWZoffset(dest_reg, JTOC, TOCoffset);
    }
  }

  public final void emitLIntOffset(GPR RT, GPR RA, Offset offset) {
    if (VM.BuildFor64Addr) {
      emitLWAoffset(RT, RA, offset);
    } else {
      emitLWZoffset(RT, RA, offset);
    }
  }

  public final void emitLAddrOffset(GPR RT, GPR RA, Offset offset) {
    if (VM.BuildFor64Addr) {
      emitLDoffset(RT, RA, offset);
    } else {
      emitLWZoffset(RT, RA, offset);
    }
  }

  // -----------------------------------------------------------//
  // The following section contains assembler "macros" used by: //
  //    BaselineCompilerImpl                                             //
  //    Barriers                                             //
  // -----------------------------------------------------------//

  // Emit baseline stack overflow instruction sequence.
  // Before:   FP is current (calling) frame
  //           TR is the current RVMThread
  // After:    R0, S0 destroyed
  //

  public void emitStackOverflowCheck(int frameSize) {
    emitLAddrOffset(GPR.R0,
                    RegisterConstants.THREAD_REGISTER,
                    Entrypoints.stackLimitField.getOffset()); // R0 := &stack guard page
    emitADDI(S0, -frameSize, FP);                             // S0 := &new frame
    emitTAddrLT(S0, GPR.R0);                                  // trap if new frame below guard page
  }

  /**
   * Emit the trap pattern (trap LLT 1) we use for nullchecks on reg;
   * @param RA  The register number containing the ptr to null check
   */
  public void emitNullCheck(GPR RA) {
    // TDLLT 1 or TWLLT 1
    final int TDItemplate = 2 << 26;
    final int TWItemplate = 3 << 26;
    int mi = (VM.BuildFor64Addr ? TDItemplate : TWItemplate) | 0x2 << 21 | RA.value() << 16 | 1;
    
    appendInstruction(mi);
  }

  // Emit baseline stack overflow instruction sequence for native method prolog.
  // For the lowest Java to C transition frame in the stack, check that there is space of
  // STACK_SIZE_NATIVE words available on the stack;  enlarge stack if necessary.
  // For subsequent Java to C transition frames, check for the requested size and don't resize
  // the stack if overflow
  // Before:   FP is current (calling) frame
  //           TR is the current RVMThread
  // After:    R0, S0 destroyed
  //
  public void emitNativeStackOverflowCheck(int frameSize) {
    emitLAddrOffset(S0,
                    RegisterConstants.THREAD_REGISTER,
                    Entrypoints.jniEnvField.getOffset());      // S0 := thread.jniEnv
    emitLIntOffset(GPR.R0, S0, Entrypoints.JNIRefsTopField.getOffset()); // R0 := thread.jniEnv.JNIRefsTop
    emitCMPI(GPR.R0, 0);                                                 // check if S0 == 0 -> first native frame on stack
    ForwardReference fr1 = emitForwardBC(EQ);
    // check for enough space for requested frame size
    emitLAddrOffset(GPR.R0,
                    RegisterConstants.THREAD_REGISTER,
                    Entrypoints.stackLimitField.getOffset()); // R0 := &stack guard page
    emitADDI(S0, -frameSize, FP);                             // S0 := &new frame pointer
    emitTAddrLT(S0, GPR.R0);                                  // trap if new frame below guard page
    ForwardReference fr2 = emitForwardB();

    // check for enough space for STACK_SIZE_JNINATIVE
    fr1.resolve(this);
    emitLAddrOffset(GPR.R0,
                    RegisterConstants.THREAD_REGISTER,
                    Entrypoints.stackLimitField.getOffset());  // R0 := &stack guard page
    emitLVAL(S0, StackframeLayoutConstants.STACK_SIZE_JNINATIVE);
    emitSUBFC(S0, S0, FP);             // S0 := &new frame pointer

    emitCMPLAddr(GPR.R0, S0);
    ForwardReference fr3 = emitForwardBC(LE);
    emitTAddrWI(1);                                    // trap if new frame pointer below guard page
    fr2.resolve(this);
    fr3.resolve(this);
  }

  public static int getTargetOffset(int instr) {
    int opcode = (instr >>> 26) & 0x3F;
    int extendedOpcode;
    switch (opcode) {
      case 63:
        // A-form
        extendedOpcode = 0x1F & (instr >> 1);
        switch (extendedOpcode) {
          case 21:                 // fadd
          case 20:                 // fsub
          case 25:                 // fmul
          case 18:                 // fdiv
            return 21;                // bits 6-11
        }
        // X-form
        extendedOpcode = 0x3FF & (instr >> 1);
        switch (extendedOpcode) {
          case 40:                 // fneg
          case 12:                 // fsrp
            return 21;                // bits 6-11
        }
        break;
      case 59:
        // A-form
        extendedOpcode = 0x1F & (instr >> 1);
        switch (extendedOpcode) {
          case 21:                 // fadds
          case 20:                 // fsubs
          case 25:                 // fmuls
          case 18:                 // fdivs
            return 21;                // bits 6-11
        }
        break;
      case 31:
        // X-form
        extendedOpcode = 0x3FF & (instr >> 1);
        switch (extendedOpcode) {
          case 24:                 // slw
          case 792:                 // sraw
          case 536:                 // srw
          case 28:                 // and
          case 444:                 // or
          case 316:                 // xor
          case 824:                 // srawi
            return 16;              // bits 11-15
        }
        // XO-form
        extendedOpcode = 0x1FF & (instr >> 1);
        switch (extendedOpcode) {
          case 266:                 // add
          case 10:                 // addc
          case 8:                 // subfc
          case 235:                 // mullw
          case 491:                 // divw
          case 104:                 // neg
            return 21;                // bits 6-11
        }
        break;
      case 28:                    // andi
        // D-form
        return 16;
    }
    return -1;
  }

  public boolean retargetInstruction(int mcIndex, int newRegister) {

    int instr = machineCodes[mcIndex];
    int offset = getTargetOffset(instr);
    if (offset < 0) {
      VM.sysWrite("Failed to retarget index=");
      VM.sysWrite(mcIndex);
      VM.sysWrite(", instr=");
      VM.sysWriteHex(instr);
      VM.sysWriteln();
      if (VM.VerifyAssertions) VM._assert(NOT_REACHED);
      return false;
    }

    instr = (instr & ~(0x1F << offset)) | (newRegister << offset);

    machineCodes[mcIndex] = instr;
    return true;
  }

  /************************************************************************
   * Stub/s added for IA32 compatability
   */
  public static void patchCode(CodeArray code, int indexa, int indexb) {
    if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
  }

  /**
   * The following method will emit code that moves a reference to an
   * object's TIB into a destination register.
   *
   * @param dest the number of the destination register
   * @param object the number of the register holding the object reference
   */
  public final void baselineEmitLoadTIB(MachineRegister dest, MachineRegister object) {
    Offset tibOffset = JavaHeader.getTibOffset();
    emitLAddrOffset((GPR)dest, (GPR)object, tibOffset);
  }
}
