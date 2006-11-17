/*
 * This file is part of Jikes RVM (http://jikesrvm.sourceforge.net).
 * The Jikes RVM project is distributed under the Common Public License (CPL).
 * A copy of the license is included in the distribution, and is also
 * available at http://www.opensource.org/licenses/cpl1.0.php
 *
 * (C) Copyright IBM Corp. 2001
 */
//$Id$
package com.ibm.jikesrvm.opt;

import com.ibm.jikesrvm.*;
import com.ibm.jikesrvm.classloader.*;
import com.ibm.jikesrvm.opt.ir.*;
import org.vmmagic.unboxed.Offset;

/**
 * Normalize the use of constants in the LIR
 * to match the patterns supported in LIR2MIR.rules
 *
 * @author Dave Grove
 */
abstract class OPT_NormalizeConstants implements OPT_Operators {

  /**
   * Only thing we do for IA32 is to restrict the usage of 
   * String, Float, and Double constants.  The rules are prepared 
   * to deal with everything else.
   * 
   * @param ir IR to normalize
   */
  static void perform(OPT_IR ir) { 
    for (OPT_Instruction s = ir.firstInstructionInCodeOrder(); 
         s != null; 
         s = s.nextInstructionInCodeOrder()) {

      // Get 'large' constants into a form the the BURS rules are 
      // prepared to deal with.
      // Constants can't appear as defs, so only scan the uses.
      //
      int numUses = s.getNumberOfUses();
      if (numUses > 0) {
        int numDefs = s.getNumberOfDefs();
        for (int idx = numDefs; idx < numUses + numDefs; idx++) {
          OPT_Operand use = s.getOperand(idx);
          if (use != null) {
            if (use instanceof OPT_StringConstantOperand) {
              OPT_RegisterOperand rop = ir.regpool.makeTemp(VM_TypeReference.JavaLangString);
              OPT_Operand jtoc = ir.regpool.makeJTOCOp(ir,s);
              OPT_StringConstantOperand sc = (OPT_StringConstantOperand)use;
              Offset offset = sc.offset;
              if (offset.isZero())
                throw new OPT_OptimizingCompilerException("String constant w/o valid JTOC offset");
              OPT_LocationOperand loc = new OPT_LocationOperand(offset);
              s.insertBefore(Load.create(INT_LOAD, rop, jtoc, new OPT_IntConstantOperand(offset.toInt()), loc));
              s.putOperand(idx, rop.copyD2U());
            } else if (use instanceof OPT_ClassConstantOperand) {
              OPT_RegisterOperand rop = ir.regpool.makeTemp(VM_TypeReference.JavaLangClass);
              OPT_Operand jtoc = ir.regpool.makeJTOCOp(ir,s);
              OPT_ClassConstantOperand cc = (OPT_ClassConstantOperand)use;
              Offset offset = cc.offset;
              if (offset.isZero())
                throw new OPT_OptimizingCompilerException("Class constant w/o valid JTOC offset");
              OPT_LocationOperand loc = new OPT_LocationOperand(offset);
              s.insertBefore(Load.create(INT_LOAD, rop, jtoc, new OPT_IntConstantOperand(offset.toInt()), loc));
              s.putOperand(idx, rop.copyD2U());
            } else if (use instanceof OPT_DoubleConstantOperand) {
              OPT_RegisterOperand rop = ir.regpool.makeTemp(VM_TypeReference.Double);
              OPT_Operand jtoc = ir.regpool.makeJTOCOp(ir,s);
              OPT_DoubleConstantOperand dc = (OPT_DoubleConstantOperand)use.copy();
              if (dc.offset.isZero()) {
                dc.offset = Offset.fromIntSignExtend(VM_Statics.findOrCreateLongSizeLiteral(Double.doubleToLongBits(dc.value)));
              }
              s.insertBefore(Binary.create(MATERIALIZE_FP_CONSTANT, rop, jtoc, dc));
              s.putOperand(idx, rop.copyD2U());
            } else if (use instanceof OPT_FloatConstantOperand) {
              OPT_RegisterOperand rop = ir.regpool.makeTemp(VM_TypeReference.Float);
              OPT_Operand jtoc = ir.regpool.makeJTOCOp(ir,s);
              OPT_FloatConstantOperand fc = (OPT_FloatConstantOperand)use.copy();
              if (fc.offset.isZero()) {
                fc.offset = Offset.fromIntSignExtend(VM_Statics.findOrCreateIntSizeLiteral(Float.floatToIntBits(fc.value)));
              }
              s.insertBefore(Binary.create(MATERIALIZE_FP_CONSTANT, rop, jtoc, fc));
              s.putOperand(idx, rop.copyD2U());
            } else if (use instanceof OPT_NullConstantOperand) {
              s.putOperand(idx, new OPT_IntConstantOperand(0));
            } else if (use instanceof OPT_AddressConstantOperand) {
              int v = ((OPT_AddressConstantOperand)use).value.toInt();
              s.putOperand(idx, new OPT_IntConstantOperand(v));
            }
          }
        }
      }
    }
  }

  /**
   * IA32 supports 32 bit int immediates, so nothing to do.
   */
  static OPT_Operand asImmediateOrReg (OPT_Operand addr, 
                                       OPT_Instruction s, 
                                       OPT_IR ir) {
    return addr;
  }

}