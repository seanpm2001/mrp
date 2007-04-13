/*
 * This file is part of Jikes RVM (http://jikesrvm.sourceforge.net).
 * The Jikes RVM project is distributed under the Common Public License (CPL).
 * A copy of the license is included in the distribution, and is also
 * available at http://www.opensource.org/licenses/cpl1.0.php
 *
 * (C) Copyright IBM Corp. 2001
 */
package org.jikesrvm.compilers.opt;

import org.jikesrvm.*;

import org.jikesrvm.compilers.opt.ir.*;
import static org.jikesrvm.compilers.opt.ir.OPT_Operators.*;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Word;
import static org.jikesrvm.compilers.opt.ir.OPT_IRTools.AC;
import static org.jikesrvm.compilers.opt.ir.OPT_IRTools.DC;
import static org.jikesrvm.compilers.opt.ir.OPT_IRTools.FC;
import static org.jikesrvm.compilers.opt.ir.OPT_IRTools.IC;
import static org.jikesrvm.compilers.opt.ir.OPT_IRTools.LC;

/**
 * This class simplifies expressions in SSA form.
 * 
 * @author Stephen Fink
 * @author Ian Rogers
 * @author Michael Baer
 */
class OPT_ExpressionFolding {
  /**
   * Only fold operations when the result of the 1st operation becomes dead
   * after folding
   */
  private static final boolean RESTRICT_TO_DEAD_EXPRESSIONS = true;

  /**
   * Fold operations on ints
   */
  private final static boolean FOLD_INTS = true;
  /**
   * Fold operations on word like things
   */
  private final static boolean FOLD_REFS = true;
  /**
   * Fold operations on longs
   */
  private final static boolean FOLD_LONGS = true;
  /**
   * Fold operations on floats
   */
  private final static boolean FOLD_FLOATS = true;
  /**
   * Fold operations on doubles
   */
  private final static boolean FOLD_DOUBLES = true;

  /**
   * Fold binary SUB operations
   */
  private final static boolean FOLD_SUBS = true;

  /**
   * Fold binary ADD operations
   */
  private final static boolean FOLD_ADDS = true;

  /**
   * Fold binary multiply operations
   */
  private final static boolean FOLD_MULTS = true;

  /**
   * Fold binary divide operations
   */
  private final static boolean FOLD_DIVS = true;

  /**
   * Fold binary shift left operations
   */
  private final static boolean FOLD_SHIFTLS = true;

  /**
   * Fold binary shift right operations
   */
  private final static boolean FOLD_SHIFTRS = true;

  /**
   * Fold binary CMP operations
   */
  private final static boolean FOLD_CMPS = true;

  /**
   * Fold binary XOR operations
   */
  private final static boolean FOLD_XORS = true;

  /**
   * Fold binary OR operations
   */
  private final static boolean FOLD_ORS = true;

  /**
   * Fold binary AND operations
   */
  private final static boolean FOLD_ANDS = true;

  /**
   * Fold unary NEG operations
   */
  private final static boolean FOLD_NEGS = true;

  /**
   * Fold unary NOT operations
   */
  private final static boolean FOLD_NOTS = true;

  /**
   * Fold operations that create a constant on the LHS? This may produce less
   * optimal code on 2 address architectures where the constant would need
   * loading into a register prior to the operation.
   */
  private final static boolean FOLD_CONSTANTS_TO_LHS = true;

  /**
   * Fold IFCMP operations
   */
  private final static boolean FOLD_IFCMPS = true;

  /**
   * Fold COND_MOVE operations
   */
  private final static boolean FOLD_CONDMOVES = true;

  /**
   * Fold xxx_2xxx where the precision is increased then decreased achieving a
   * nop effect
   */
  private final static boolean FOLD_2CONVERSION = true;

  /**
   * Perform the transformation.
   * 
   * If we have, in SSA form,
   * 
   * <pre>
   *    x = a op1 c1
   *    y = x op2 c2
   * </pre>
   * 
   * where c1 and c2 are constants, replace the def of y by
   * 
   * <pre>
   * y = a op1 (c1 op3 c2)
   * </pre>
   * 
   * Where op1, op2 and op3 are add, subtract, multiply, and, or, xor and
   * compare. Repeatedly apply transformation until all expressions are folded.
   * 
   * <p>
   * PRECONDITIONS: SSA form, register lists computed
   * 
   * @param ir
   *          the governing IR
   */
  public static void perform(OPT_IR ir) {
    // Create a set of potential computations to fold.
    HashSet<OPT_Register> candidates = new HashSet<OPT_Register>(20);

    for (Enumeration<OPT_Instruction> e = ir.forwardInstrEnumerator(); e.hasMoreElements();) {
      OPT_Instruction s = e.nextElement();
      // Check if s is a candidate for expression folding
      OPT_Register r = isCandidateExpression(s);
      if (r != null) {
        candidates.add(r);
      }
    }

    if (RESTRICT_TO_DEAD_EXPRESSIONS) {
      pruneCandidates(candidates);
    }

    boolean didSomething = true;
    while (didSomething) {
      didSomething = false;

      iterate_over_candidates:
      for (Iterator<OPT_Register> it = candidates.iterator(); it.hasNext();) {
        OPT_Register r = it.next();
        OPT_Instruction s = r.getFirstDef();
        OPT_Operand val1;

        if (Binary.conforms(s))
          val1 = Binary.getVal1(s);
        else if (Unary.conforms(s)) {
          val1 = Unary.getVal(s);
        } else if (BooleanCmp.conforms(s)) {
          val1 = BooleanCmp.getVal1(s);
        } else if (IfCmp.conforms(s)) {
          val1 = IfCmp.getVal1(s);
        } else if (IfCmp2.conforms(s)) {
          val1 = IfCmp2.getVal1(s);
        } else if (CondMove.conforms(s)) {
          val1 = CondMove.getVal1(s);
        } else {
          // we're not optimising any other instruction types
          continue;
        }

        if (candidates.contains(val1.asRegister().register)) {
          OPT_Instruction def = val1.asRegister().register.getFirstDef();

          // filter out moves to get the real defining instruction
          while (Move.conforms(def)) {
            OPT_Operand op = Move.getVal(def);
            if (op.isRegister()){
              def = op.asRegister().register.getFirstDef();
            }
            else {
              // The non-constant operand of the candidate expression is the
              // result of moving a constant. Remove as a candidate and leave
              // for constant propogation and simplification.
              it.remove();
              continue iterate_over_candidates;
            }
          }
          
          // check if the defining instruction has not mutated yet
          if (isCandidateExpression(def) == null)
            continue;

          OPT_Instruction newS = transform(s, def);
          if (newS != null) {
            // check if this expression is still an optimisation candidate
            if (isCandidateExpression(newS) == null)
              it.remove();

            s.insertAfter(newS);
            OPT_DefUse.updateDUForNewInstruction(newS);
            OPT_DefUse.removeInstructionAndUpdateDU(s);
            didSomething = true;
          }
        }
      }
    }
  }

  /**
   * Prune the candidate set; restrict candidates to only allow transformations
   * that result in dead code to be eliminated
   */
  private static void pruneCandidates(HashSet<OPT_Register> candidates) {
    for (Iterator<OPT_Register> i = candidates.iterator(); i.hasNext();) {
      OPT_Register r = i.next();
      OPT_Instruction s = r.getFirstDef();
      OPT_Operand val1;
      if (Binary.conforms(s)) {
        val1 = Binary.getVal1(s);
      } else if (GuardedBinary.conforms(s)) {
        val1 = GuardedBinary.getVal1(s);
      } else if (Unary.conforms(s)) {
        val1 = Unary.getVal(s);
      } else if (BooleanCmp.conforms(s)) {
        val1 = BooleanCmp.getVal1(s);
      } else if (IfCmp.conforms(s)) {
        val1 = IfCmp.getVal1(s);
      } else if (IfCmp2.conforms(s)) {
        val1 = IfCmp2.getVal1(s);
      } else if (CondMove.conforms(s)) {
        val1 = CondMove.getVal1(s);
      } else {
        OPT_OptimizingCompilerException.UNREACHABLE();
        return;
      }

      if (VM.VerifyAssertions)
        VM._assert(val1.isRegister(), "Error with val1 of " + s);

      OPT_Register v1 = val1.asRegister().register;
      if (candidates.contains(v1)) {
        for (Enumeration<OPT_RegisterOperand> uses = OPT_DefUse.uses(v1); uses
            .hasMoreElements();) {
          OPT_RegisterOperand op = uses.nextElement();
          OPT_Instruction u = op.instruction;
          if ((isCandidateExpression(u) == null) && !Move.conforms(u)) {
            i.remove();
            break;
          }
        }
      }
    }
  }

  /**
   * Perform the transfomation on the instruction
   * 
   * @param s
   *          the instruction to transform of the form y = x op c1
   * @param def
   *          the definition of x, the defining instruction is of the form x = a
   *          op c2
   * @return the new instruction to replace s;
   */
  private static OPT_Instruction transform(OPT_Instruction s, 
      OPT_Instruction def) {
    // x = a op1 c1
    // y = x op2 c2
    OPT_RegisterOperand a;
    OPT_RegisterOperand y;
    if (Binary.conforms(def)) {
      a = Binary.getVal1(def).asRegister();
    }
    else if (GuardedBinary.conforms(def)) {
      a = GuardedBinary.getVal1(def).asRegister();
    }
    else if (Unary.conforms(def)) {
      a = Unary.getVal(def).asRegister();
    }
    else if (BooleanCmp.conforms(def) || IfCmp.conforms(def) ||
        IfCmp2.conforms(def) || CondMove.conforms(def)) {
      // we don't fold in case of a Boolean/IfCmp/CondMove coming before
      // the instruction to be folded
      return null;
    }
    else {
      OPT_OptimizingCompilerException.UNREACHABLE();
      return null;
    }

    if (Binary.conforms(s)) {
      y = Binary.getResult(s);
    }
    else if (GuardedBinary.conforms(s)) {
      y = GuardedBinary.getResult(s);
    }
    else if (Unary.conforms(s)) {
      y = Unary.getResult(s);
    }
    else if (BooleanCmp.conforms(s)) { 
      y = BooleanCmp.getResult(s);
    }
    else if (IfCmp.conforms(s)) { 
      y = IfCmp.getGuardResult(s);
    }
    else if (IfCmp2.conforms(s)) {
      y = IfCmp2.getGuardResult(s);
    }
    else if (CondMove.conforms(s)) {
      y = CondMove.getResult(s);
    }
    else {
      OPT_OptimizingCompilerException.UNREACHABLE();
      return null;
    }

    if(VM.VerifyAssertions) {
      VM._assert(a.isRegister(), "Expected register not " + a);
      VM._assert(y.isRegister(), "Expected register not " + y);
    }
    
    switch(s.operator.opcode) {
    // Foldable operators
    case INT_ADD_opcode: {
      if (FOLD_INTS && FOLD_ADDS) {
        int c2 = getIntValue(Binary.getVal2(s));
        if (def.operator == INT_ADD) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a + c1; y = x + c2
          return Binary.create(INT_ADD, y.copyRO(), a.copyRO(), IC(c1+c2));
        }
        else if (def.operator == INT_SUB) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a - c1; y = x + c2
          return Binary.create(INT_ADD, y.copyRO(), a.copyRO(), IC(c2-c1));
        }
        else if (def.operator == INT_NEG && FOLD_CONSTANTS_TO_LHS) {
          // x = -a; y = x + c2;
          return Binary.create(INT_SUB, y.copyRO(), IC(c2), a.copyRO());
        }
      }
      return null;
    }
    case REF_ADD_opcode: {
      if (FOLD_REFS && FOLD_ADDS) {
        Address c2 = getAddressValue(Binary.getVal2(s));
        if (def.operator == REF_ADD) {
          Address c1 = getAddressValue(Binary.getVal2(def));
          // x = a + c1; y = x + c2
          return Binary.create(REF_ADD, y.copyRO(), a.copyRO(), AC(c1.toWord().plus(c2.toWord()).toAddress()));
        }
        else if (def.operator == REF_SUB) {
          Address c1 = getAddressValue(Binary.getVal2(def));
          // x = a - c1; y = x + c2
          return Binary.create(REF_ADD, y.copyRO(), a.copyRO(), AC(c2.toWord().minus(c1.toWord()).toAddress()));
        }
        else if (def.operator == REF_NEG && FOLD_CONSTANTS_TO_LHS) {
          // x = -a; y = x + c2;
          return Binary.create(REF_SUB, y.copyRO(), AC(c2), a.copyRO());
        }
      }
      return null;
    }
    case LONG_ADD_opcode: {
      if (FOLD_LONGS && FOLD_ADDS) {
        long c2 = getLongValue(Binary.getVal2(s));
        if (def.operator == LONG_ADD) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a + c1; y = x + c2
          return Binary.create(LONG_ADD, y.copyRO(), a.copyRO(), LC(c1+c2));
        }
        else if (def.operator == LONG_SUB) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a - c1; y = x + c2
          return Binary.create(LONG_ADD, y.copyRO(), a.copyRO(), LC(c2-c1));
        }
        else if (def.operator == LONG_NEG && FOLD_CONSTANTS_TO_LHS) {
          // x = -a; y = x + c2;
          return Binary.create(LONG_SUB, y.copyRO(), LC(c2), a.copyRO());
        }
      }
      return null;
    }
    case FLOAT_ADD_opcode: {
      if (FOLD_FLOATS && FOLD_ADDS) {
        float c2 = getFloatValue(Binary.getVal2(s));
        if (def.operator == FLOAT_ADD) {
          float c1 = getFloatValue(Binary.getVal2(def));
          // x = a + c1; y = x + c2
          return Binary.create(FLOAT_ADD, y.copyRO(), a.copyRO(), FC(c1+c2));
        }
        else if (def.operator == FLOAT_SUB) {
          float c1 = getFloatValue(Binary.getVal2(def));
          // x = a - c1; y = x + c2
          return Binary.create(FLOAT_ADD, y.copyRO(), a.copyRO(), FC(c2-c1));
        }
        else if (def.operator == FLOAT_NEG && FOLD_CONSTANTS_TO_LHS) {
          // x = -a; y = x + c2;
          return Binary.create(FLOAT_SUB, y.copyRO(), FC(c2), a.copyRO());
        }
      }
      return null;
    }
    case DOUBLE_ADD_opcode: {
      if (FOLD_DOUBLES && FOLD_ADDS) {
        double c2 = getDoubleValue(Binary.getVal2(s));
        if (def.operator == DOUBLE_ADD) {
          double c1 = getDoubleValue(Binary.getVal2(def));
          // x = a + c1; y = x + c2
          return Binary.create(DOUBLE_ADD, y.copyRO(), a.copyRO(), DC(c1+c2));
        }
        else if (def.operator == DOUBLE_SUB) {
          double c1 = getDoubleValue(Binary.getVal2(def));
          // x = a - c1; y = x + c2
          return Binary.create(DOUBLE_ADD, y.copyRO(), a.copyRO(), DC(c2-c1));
        }
        else if (def.operator == DOUBLE_NEG && FOLD_CONSTANTS_TO_LHS) {
          // x = -a; y = x + c2;
          return Binary.create(DOUBLE_SUB, y.copyRO(), DC(c2), a.copyRO());
        }
      }
      return null;
    }
    case INT_SUB_opcode: {
      if (FOLD_INTS && FOLD_SUBS) {
        int c2 = getIntValue(Binary.getVal2(s));
        if (def.operator == INT_ADD) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a + c1; y = x - c2
          return Binary.create(INT_ADD, y.copyRO(), a.copyRO(), IC(c1-c2));
        }
        else if (def.operator == INT_SUB) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a - c1; y = x - c2
          return Binary.create(INT_ADD, y.copyRO(), a.copyRO(), IC(-c1-c2));
        }
        else if (def.operator == INT_NEG && FOLD_CONSTANTS_TO_LHS) {
          // x = -a; y = x - c2;
          return Binary.create(INT_SUB, y.copyRO(), IC(-c2), a.copyRO());
        }
      }
      return null;
    }
    case REF_SUB_opcode: {
      if (FOLD_REFS && FOLD_SUBS) {
        Address c2 = getAddressValue(Binary.getVal2(s));
        if (def.operator == REF_ADD) {
          Address c1 = getAddressValue(Binary.getVal2(def));
          // x = a + c1; y = x - c2
          return Binary.create(REF_ADD, y.copyRO(), a.copyRO(), AC(c1.toWord().minus(c2.toWord()).toAddress()));
        }
        else if (def.operator == REF_SUB) {
          Address c1 = getAddressValue(Binary.getVal2(def));
          // x = a - c1; y = x - c2
          return Binary.create(REF_ADD, y.copyRO(), a.copyRO(), AC(Word.zero().minus(c1.toWord()).minus(c2.toWord()).toAddress()));
        }
        else if (def.operator == REF_NEG && FOLD_CONSTANTS_TO_LHS) {
          // x = -a; y = x - c2;
          return Binary.create(REF_SUB, y.copyRO(), AC(Word.zero().minus(c2.toWord()).toAddress()), a.copyRO());
        }
      }
      return null;
    }
    case LONG_SUB_opcode: {
      if (FOLD_LONGS && FOLD_SUBS) {
        long c2 = getLongValue(Binary.getVal2(s));
        if (def.operator == LONG_ADD) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a + c1; y = x - c2
          return Binary.create(LONG_ADD, y.copyRO(), a.copyRO(), LC(c1-c2));
        }
        else if (def.operator == LONG_SUB) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a - c1; y = x - c2
          return Binary.create(LONG_ADD, y.copyRO(), a.copyRO(), LC(-c1-c2));
        }
        else if (def.operator == LONG_NEG && FOLD_CONSTANTS_TO_LHS) {
          // x = -a; y = x - c2;
          return Binary.create(LONG_SUB, y.copyRO(), LC(-c2), a.copyRO());
        }
      }
      return null;
    }
    case FLOAT_SUB_opcode: {
      if (FOLD_FLOATS && FOLD_SUBS) {
        float c2 = getFloatValue(Binary.getVal2(s));
        if (def.operator == FLOAT_ADD) {
          float c1 = getFloatValue(Binary.getVal2(def));
          // x = a + c1; y = x - c2
          return Binary.create(FLOAT_ADD, y.copyRO(), a.copyRO(), FC(c1-c2));
        }
        else if (def.operator == FLOAT_SUB) {
          float c1 = getFloatValue(Binary.getVal2(def));
          // x = a - c1; y = x - c2
          return Binary.create(FLOAT_ADD, y.copyRO(), a.copyRO(), FC(-c1-c2));
        }
        else if (def.operator == FLOAT_NEG && FOLD_CONSTANTS_TO_LHS) {
          // x = -a; y = x - c2;
          return Binary.create(FLOAT_SUB, y.copyRO(), FC(-c2), a.copyRO());
        }
      }
      return null;
    }
    case DOUBLE_SUB_opcode: {
      if (FOLD_DOUBLES && FOLD_SUBS) {
        double c2 = getDoubleValue(Binary.getVal2(s));
        if (def.operator == FLOAT_ADD) {
          double c1 = getDoubleValue(Binary.getVal2(def));
          // x = a + c1; y = x - c2
          return Binary.create(DOUBLE_ADD, y.copyRO(), a.copyRO(), DC(c1-c2));
        }
        else if (def.operator == DOUBLE_SUB) {
          double c1 = getDoubleValue(Binary.getVal2(def));
          // x = a - c1; y = x + c2
          return Binary.create(DOUBLE_ADD, y.copyRO(), a.copyRO(), DC(-c1-c2));
        }
        else if (def.operator == DOUBLE_NEG && FOLD_CONSTANTS_TO_LHS) {
          // x = -a; y = x - c2;
          return Binary.create(DOUBLE_SUB, y.copyRO(), DC(-c2), a.copyRO());
        }
      }
      return null;
    }
    case INT_MUL_opcode: {
      if (FOLD_INTS && FOLD_MULTS) {
        int c2 = getIntValue(Binary.getVal2(s));
        if (def.operator == INT_MUL) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a * c1; y = x * c2
          return Binary.create(INT_MUL, y.copyRO(), a.copyRO(), IC(c1*c2));
        }
        else if (def.operator == INT_NEG) {
          // x = -a; y = x * c2;
          return Binary.create(INT_MUL, y.copyRO(), a.copyRO(), IC(-c2));
        }
      }
      return null;
    }
    case LONG_MUL_opcode: {
      if (FOLD_LONGS && FOLD_MULTS) {
        long c2 = getLongValue(Binary.getVal2(s));
        if (def.operator == LONG_MUL) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a * c1; y = x * c2
          return Binary.create(LONG_MUL, y.copyRO(), a.copyRO(), LC(c1*c2));
        }
        else if (def.operator == LONG_NEG) {
          // x = -a; y = x * c2;
          return Binary.create(LONG_MUL, y.copyRO(), a.copyRO(), LC(-c2));
        }
      }
      return null;
    }
    case FLOAT_MUL_opcode: {
      if (FOLD_FLOATS && FOLD_MULTS) {
        float c2 = getFloatValue(Binary.getVal2(s));
        if (def.operator == FLOAT_MUL) {
          float c1 = getFloatValue(Binary.getVal2(def));
          // x = a * c1; y = x * c2
          return Binary.create(FLOAT_MUL, y.copyRO(), a.copyRO(), FC(c1*c2));
        }
        else if (def.operator == FLOAT_NEG) {
          // x = -a; y = x * c2;
          return Binary.create(FLOAT_MUL, y.copyRO(), a.copyRO(), FC(-c2));
        }
      }
      return null;
    }
    case DOUBLE_MUL_opcode: {
      if (FOLD_DOUBLES && FOLD_MULTS) {
        double c2 = getDoubleValue(Binary.getVal2(s));
        if (def.operator == DOUBLE_MUL) {
          double c1 = getDoubleValue(Binary.getVal2(def));
          // x = a * c1; y = x * c2
          return Binary.create(DOUBLE_MUL, y.copyRO(), a.copyRO(), DC(c1*c2));
        }
        else if (def.operator == DOUBLE_NEG) {
          // x = -a; y = x * c2;
          return Binary.create(DOUBLE_MUL, y.copyRO(), a.copyRO(), DC(-c2));
        }
      }
      return null;
    }
    case INT_DIV_opcode: {
      if (FOLD_INTS && FOLD_DIVS) {
        int c2 = getIntValue(GuardedBinary.getVal2(s));
        if (def.operator == INT_DIV) {
          int c1 = getIntValue(GuardedBinary.getVal2(def));
          OPT_Operand guard = GuardedBinary.getGuard(def);
          // x = a / c1; y = x / c2
          return GuardedBinary.create(INT_DIV, y.copyRO(), a.copyRO(), IC(c1*c2), guard);
        }
        else if (def.operator == INT_NEG) {
          OPT_Operand guard = GuardedBinary.getGuard(def);
          // x = -a; y = x / c2;
          return GuardedBinary.create(INT_DIV, y.copyRO(), a.copyRO(), IC(-c2), guard);
        }
      }
      return null;
    }
    case LONG_DIV_opcode: {
      if (FOLD_LONGS && FOLD_DIVS) {
        long c2 = getLongValue(GuardedBinary.getVal2(s));
        if (def.operator == LONG_DIV) {
          long c1 = getLongValue(GuardedBinary.getVal2(def));
          OPT_Operand guard = GuardedBinary.getGuard(def);
          // x = a / c1; y = x / c2
          return GuardedBinary.create(LONG_DIV, y.copyRO(), a.copyRO(), LC(c1*c2), guard);
        }
        else if (def.operator == LONG_NEG) {
          OPT_Operand guard = GuardedBinary.getGuard(def);
          // x = -a; y = x / c2;
          return GuardedBinary.create(LONG_DIV, y.copyRO(), a.copyRO(), LC(-c2), guard);
        }
      }
      return null;
    }
    case FLOAT_DIV_opcode: {
      if (FOLD_FLOATS && FOLD_DIVS) {
        float c2 = getFloatValue(Binary.getVal2(s));
        if (def.operator == FLOAT_DIV) {
          float c1 = getFloatValue(Binary.getVal2(def));
          // x = a / c1; y = x / c2
          return Binary.create(FLOAT_DIV, y.copyRO(), a.copyRO(), FC(c1*c2));
        }
        else if (def.operator == FLOAT_NEG) {
          // x = -a; y = x / c2;
          return Binary.create(FLOAT_DIV, y.copyRO(), a.copyRO(), FC(-c2));
        }
      }
      return null;
    }
    case DOUBLE_DIV_opcode: {
      if (FOLD_DOUBLES && FOLD_DIVS) {
        double c2 = getDoubleValue(Binary.getVal2(s));
        if (def.operator == DOUBLE_DIV) {
          double c1 = getDoubleValue(Binary.getVal2(def));
          // x = a / c1; y = x / c2
          return Binary.create(DOUBLE_DIV, y.copyRO(), a.copyRO(), DC(c1*c2));
        }
        else if (def.operator == DOUBLE_NEG) {
          // x = -a; y = x / c2;
          return Binary.create(DOUBLE_DIV, y.copyRO(), a.copyRO(), DC(-c2));
        }
      }
      return null;
    }
    case INT_SHL_opcode: {
      if (FOLD_INTS && FOLD_SHIFTLS) {
        int c2 = getIntValue(Binary.getVal2(s));
        if (def.operator == INT_SHL) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a << c1; y = x << c2
          return Binary.create(INT_SHL, y.copyRO(), a.copyRO(), IC(c1+c2));
        }
        else if ((def.operator == INT_SHR) || (def.operator == INT_USHR)) {
          int c1 = getIntValue(Binary.getVal2(def));
          if (c1 == c2) {
            // x = a >> c1; y = x << c1
            return Binary.create(INT_AND, y.copyRO(), a.copyRO(),
                IC(-1 << c1));
          }
        }
      }
      return null;
    }
    case REF_SHL_opcode: {
      if (FOLD_REFS && FOLD_SHIFTLS) {
        int c2 = getIntValue(Binary.getVal2(s));
        if (def.operator == REF_SHL) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a << c1; y = x << c2
          return Binary.create(REF_SHL, y.copyRO(), a.copyRO(), IC(c1+c2));
        }
        else if ((def.operator == REF_SHR) || (def.operator == REF_USHR)) {
          int c1 = getIntValue(Binary.getVal2(def));
          if (c1 == c2) {
            // x = a >> c1; y = x << c1
            return Binary.create(REF_AND, y.copyRO(), a.copyRO(),
                AC(Word.zero().minus(Word.one()).lsh(c1).toAddress()));
          }
        }
      }
      return null;
    }
    case LONG_SHL_opcode: {
      if (FOLD_LONGS && FOLD_SHIFTLS) {
        int c2 = getIntValue(Binary.getVal2(s));
        if (def.operator == LONG_SHL) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a << c1; y = x << c2
          return Binary.create(LONG_SHL, y.copyRO(), a.copyRO(), IC(c1+c2));
        }
        else if ((def.operator == LONG_SHR) || (def.operator == LONG_USHR)) {
          int c1 = getIntValue(Binary.getVal2(def));
          if (c1 == c2) {
            // x = a >> c1; y = x << c1
            return Binary.create(LONG_AND, y.copyRO(), a.copyRO(),
                LC(-1L << c1));
          }
        }
      }
      return null;
    }
    case INT_SHR_opcode: {
      if (FOLD_INTS && FOLD_SHIFTRS) {
        int c2 = getIntValue(Binary.getVal2(s));
        if (def.operator == INT_SHR) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a >> c1; y = x >> c2
          return Binary.create(INT_SHR, y.copyRO(), a.copyRO(), IC(c1+c2));
        }
        else if (def.operator == INT_SHL) {
          int c1 = getIntValue(Binary.getVal2(def));
          if (c1 == c2) {
            if (c1 == 24) {
              // x = a << 24; y = x >> 24
              return Unary.create(INT_2BYTE, y.copyRO(), a.copyRO());
            }
            else if (c1 == 16) {
              // x = a << 16; y = x >> 16
              return Unary.create(INT_2SHORT, y.copyRO(), a.copyRO());
            }
          }
        }
      }
      return null;
    }
    case REF_SHR_opcode: {
      if (FOLD_REFS && FOLD_SHIFTRS) {
        int c2 = getIntValue(Binary.getVal2(s));
        if (def.operator == REF_SHR) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a >> c1; y = x >> c2
          return Binary.create(REF_SHR, y.copyRO(), a.copyRO(), IC(c1+c2));
        }
      }
      return null;
    }
    case LONG_SHR_opcode: {
      if (FOLD_LONGS && FOLD_SHIFTRS) {
        int c2 = getIntValue(Binary.getVal2(s));
        if (def.operator == LONG_SHR) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a >> c1; y = x >> c2
          return Binary.create(LONG_SHR, y.copyRO(), a.copyRO(), IC(c1+c2));
        }
      }
      return null;
    }
    case INT_USHR_opcode: {
      if (FOLD_INTS && FOLD_SHIFTRS) {
        int c2 = getIntValue(Binary.getVal2(s));
        if (def.operator == INT_USHR) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a >>> c1; y = x >>> c2
          return Binary.create(INT_USHR, y.copyRO(), a.copyRO(), IC(c1+c2));
        }
        else if (def.operator == INT_SHL) {
          int c1 = getIntValue(Binary.getVal2(def));
          if (c1 == c2) {
            // x = a << c1; y = x >>> c1
            return Binary.create(INT_AND, y.copyRO(), a.copyRO(),
                IC(-1 >>> c1));
          }
        }
      }
      return null;
    }
    case REF_USHR_opcode: {
      if (FOLD_REFS && FOLD_SHIFTRS) {
        int c2 = getIntValue(Binary.getVal2(s));
        if (def.operator == REF_USHR) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a >>> c1; y = x >>> c2
          return Binary.create(REF_USHR, y.copyRO(), a.copyRO(), IC(c1+c2));
        }
        else if (def.operator == REF_SHL) {
          int c1 = getIntValue(Binary.getVal2(def));
          if (c1 == c2) {
            // x = a << c1; y = x >>> c1
            return Binary.create(REF_AND, y.copyRO(), a.copyRO(),
                AC(Word.zero().minus(Word.one()).rshl(c1).toAddress()));
          }
        }
      }
      return null;
    }
    case LONG_USHR_opcode: {
      if (FOLD_LONGS && FOLD_SHIFTRS) {
        int c2 = getIntValue(Binary.getVal2(s));
        if (def.operator == LONG_USHR) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a >>> c1; y = x >>> c2
          return Binary.create(LONG_USHR, y.copyRO(), a.copyRO(), IC(c1+c2));
        }
        else if (def.operator == LONG_SHL) {
          int c1 = getIntValue(Binary.getVal2(def));
          if (c1 == c2) {
            // x = a << c1; y = x >>> c1
            return Binary.create(LONG_AND, y.copyRO(), a.copyRO(),
                LC(-1L >>> c1));
          }
        }
      }
      return null;
    }
    case INT_AND_opcode: {
      if (FOLD_INTS && FOLD_ANDS) {
        int c2 = getIntValue(Binary.getVal2(s));
        if (def.operator == INT_AND) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a & c1; y = x & c2
          return Binary.create(INT_AND, y.copyRO(), a.copyRO(), IC(c1&c2));
        }
        else if (def.operator == INT_OR) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a | c1; y = x & c2
          if ((c1 & c2) == 0) {
            return Binary.create(INT_AND, y.copyRO(), a.copyRO(), IC(c2));
          }
        }
        else if (def.operator == INT_XOR) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a ^ c1; y = x & c2
          if ((c1 & c2) == 0) {
            return Binary.create(INT_AND, y.copyRO(), a.copyRO(), IC(c2));
          }
        }
        else if (def.operator == INT_SHR) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a >> c1; y = x & c2
          if ((-1 >>> c1) == c2) {
            // turn arithmetic shifts into logical shifts if possible
            return Binary.create(INT_USHR, y.copyRO(), a.copyRO(), IC(c1));
          }
        }
        else if (def.operator == INT_SHL) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a << c1; y = x & c2
          if (((-1 << c1) & c2) == (-1 << c1)) {
            // does the mask zero bits already cleared by the shift?
            return Binary.create(INT_SHL, y.copyRO(), a.copyRO(), IC(c1));
          }
        }
        else if (def.operator == INT_USHR) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a >>> c1; y = x & c2
          if (((-1 >>> c1) & c2) == (-1 >>> c1)) {
            // does the mask zero bits already cleared by the shift?
            return Binary.create(INT_USHR, y.copyRO(), a.copyRO(), IC(c1));
          }
        }
      }
      return null;
    }
    case REF_AND_opcode: {
      if (FOLD_REFS && FOLD_ANDS) {
        Address c2 = getAddressValue(Binary.getVal2(s));
        if (def.operator == REF_AND) {
          Address c1 = getAddressValue(Binary.getVal2(def));
          // x = a & c1; y = x & c2
          return Binary.create(REF_AND, y.copyRO(), a.copyRO(), AC(c1.toWord().and(c2.toWord()).toAddress()));
        }
        else if (def.operator == REF_OR) {
          Address c1 = getAddressValue(Binary.getVal2(def));
          // x = a | c1; y = x & c2
          if (c1.toWord().and(c2.toWord()).EQ(Word.zero())) {
            return Binary.create(REF_AND, y.copyRO(), a.copyRO(), AC(c2));
          }
        }
        else if (def.operator == REF_XOR) {
          Address c1 = getAddressValue(Binary.getVal2(def));
          // x = a ^ c1; y = x & c2
          if (c1.toWord().and(c2.toWord()).EQ(Word.zero())) {
            return Binary.create(REF_AND, y.copyRO(), a.copyRO(), AC(c2));
          }
        }
        else if (def.operator == REF_SHR) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a >> c1; y = x & c2
          if (Word.zero().minus(Word.one()).rshl(c1).toAddress().EQ(c2)) {
            // turn arithmetic shifts into logical ones if possible
            return Binary.create(REF_USHR, y.copyRO(), a.copyRO(), IC(c1));
          }
        }
        else if (def.operator == REF_SHL) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a << c1; y = x & c2
          if (Word.zero().minus(Word.one()).lsh(c1).and(c2.toWord()).EQ(Word.zero().minus(Word.one()).lsh(c1))) {
            // does the mask zero bits already cleared by the shift?
            return Binary.create(REF_SHL, y.copyRO(), a.copyRO(), IC(c1));
          }
        }
        else if (def.operator == REF_USHR) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a >>> c1; y = x & c2
          if (Word.zero().minus(Word.one()).rshl(c1).and(c2.toWord()).EQ(Word.zero().minus(Word.one()).rshl(c1))) {
            // does the mask zero bits already cleared by the shift?
            return Binary.create(REF_USHR, y.copyRO(), a.copyRO(), IC(c1));
          }
        }
      }
      return null;
    }
    case LONG_AND_opcode: {
      if (FOLD_LONGS && FOLD_ANDS) {
        long c2 = getLongValue(Binary.getVal2(s));
        if (def.operator == LONG_AND) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a & c1; y = x & c2
          return Binary.create(LONG_AND, y.copyRO(), a.copyRO(), LC(c1&c2));
        }
        else if (def.operator == LONG_OR) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a | c1; y = x & c2
          if ((c1 & c2) == 0) {
            return Binary.create(LONG_AND, y.copyRO(), a.copyRO(), LC(c2));
          }
        }
        else if (def.operator == LONG_XOR) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a ^ c1; y = x & c2
          if ((c1 & c2) == 0) {
            return Binary.create(LONG_AND, y.copyRO(), a.copyRO(), LC(c2));
          }
        }
        else if (def.operator == LONG_SHR) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a >> c1; y = x & c2
          if ((-1L >>> c1) == c2) {
            // turn arithmetic shifts into logical ones if possible
            return Binary.create(LONG_USHR, y.copyRO(), a.copyRO(), IC(c1));
          }
        }
        else if (def.operator == LONG_SHL) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a << c1; y = x & c2
          if (((-1L << c1) & c2) == (-1L << c1)) {
            // does the mask zero bits already cleared by the shift?
            return Binary.create(LONG_SHL, y.copyRO(), a.copyRO(), IC(c1));
          }
        }
        else if (def.operator == LONG_USHR) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a >>> c1; y = x & c2
          if (((-1L >>> c1) & c2) == (-1L >>> c1)) {
            // does the mask zero bits already cleared by the shift?
            return Binary.create(LONG_USHR, y.copyRO(), a.copyRO(), IC(c1));
          }
        }
      }
      return null;
    }
    case INT_OR_opcode: {
      if (FOLD_INTS && FOLD_ORS) {
        int c2 = getIntValue(Binary.getVal2(s));
        if (def.operator == INT_OR) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a | c1; y = x | c2
          return Binary.create(INT_OR, y.copyRO(), a.copyRO(), IC(c1|c2));
        }
        else if (def.operator == INT_AND) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a & c1; y = x | c2
          if ((~c1 | c2) == c2) {
            return Binary.create(INT_OR, y.copyRO(), a.copyRO(), IC(c2));
          }
        }
        else if (def.operator == INT_XOR) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a ^ c1; y = x | c2
          if ((c1 | c2) == c2) {
            return Binary.create(INT_OR, y.copyRO(), a.copyRO(), IC(c2));
          }
        }
      }
      return null;
    }
    case REF_OR_opcode: {
      if (FOLD_REFS && FOLD_ORS) {
        Address c2 = getAddressValue(Binary.getVal2(s));
        if (def.operator == REF_OR) {
          Address c1 = getAddressValue(Binary.getVal2(def));
          // x = a | c1; y = x | c2
          return Binary.create(REF_OR, y.copyRO(), a.copyRO(), AC(c1.toWord().or(c2.toWord()).toAddress()));
        }
        else if (def.operator == REF_AND) {
          Address c1 = getAddressValue(Binary.getVal2(def));
          // x = a & c1; y = x | c2
          if (c1.toWord().not().or(c2.toWord()).EQ(c2.toWord())) {
            return Binary.create(REF_OR, y.copyRO(), a.copyRO(), AC(c2));
          }
        }
        else if (def.operator == REF_XOR) {
          Address c1 = getAddressValue(Binary.getVal2(def));
          // x = a ^ c1; y = x | c2
          if (c1.toWord().or(c2.toWord()).EQ(c2.toWord())) {
            return Binary.create(REF_OR, y.copyRO(), a.copyRO(), AC(c2));
          }
        }
      }
      return null;
    }
    case LONG_OR_opcode: {
      if (FOLD_LONGS && FOLD_ORS) {
        long c2 = getLongValue(Binary.getVal2(s));
        if (def.operator == LONG_OR) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a | c1; y = x | c2
          return Binary.create(LONG_OR, y.copyRO(), a.copyRO(), LC(c1|c2));
        }
        else if (def.operator == LONG_AND) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a & c1; y = x | c2
          if ((~c1 | c2) == c2) {
            return Binary.create(LONG_OR, y.copyRO(), a.copyRO(), LC(c2));
          }
        }
        else if (def.operator == LONG_XOR) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a ^ c1; y = x | c2
          if ((c1 | c2) == c2) {
            return Binary.create(LONG_OR, y.copyRO(), a.copyRO(), LC(c2));
          }
        }
      }
      return null;
    }
    case INT_XOR_opcode: {
      if (FOLD_INTS && FOLD_XORS) {
        int c2 = getIntValue(Binary.getVal2(s));
        if (def.operator == INT_XOR) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a ^ c1; y = x ^ c2
          return Binary.create(INT_XOR, y.copyRO(), a.copyRO(), IC(c1^c2));
        }
        else 
          if (def.operator == INT_NOT) {
            // x = ~a; y = x ^ c2
            return Binary.create(INT_XOR, y.copyRO(), a.copyRO(), IC(~c2));
          }
      }
      return null;
    }
    case REF_XOR_opcode: {
      if (FOLD_REFS && FOLD_XORS) {
        Address c2 = getAddressValue(Binary.getVal2(s));
        if (def.operator == REF_XOR) {
          Address c1 = getAddressValue(Binary.getVal2(def));
          // x = a ^ c1; y = x ^ c2
          return Binary.create(REF_XOR, y.copyRO(), a.copyRO(), AC(c1.toWord().xor(c2.toWord()).toAddress()));
        }
        else 
          if (def.operator == REF_NOT) {
            // x = ~a; y = x ^ c2
            return Binary.create(REF_XOR, y.copyRO(), a.copyRO(), AC(c2.toWord().not().toAddress()));
          }
      }
      return null;
    }
    case LONG_XOR_opcode: {
      if (FOLD_LONGS && FOLD_XORS) {
        long c2 = getLongValue(Binary.getVal2(s));
        if (def.operator == LONG_XOR) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a ^ c1; y = x ^ c2
          return Binary.create(LONG_XOR, y.copyRO(), a.copyRO(), LC(c1^c2));
        }
        else 
          if (def.operator == LONG_NOT) {
            // x = ~a; y = x ^ c2
            return Binary.create(LONG_XOR, y.copyRO(), a.copyRO(), LC(~c2));
          }
      }
      return null;
    }
    case LONG_CMP_opcode: {
      if (FOLD_LONGS && FOLD_CMPS) {
        long c2 = getLongValue(Binary.getVal2(s));
        if (def.operator == LONG_ADD) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a + c1; y = x cmp c2
          return Binary.create(LONG_CMP, y.copyRO(), a.copyRO(), LC(c2-c1));
        }
        else if (def.operator == LONG_SUB) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a - c1; y = x cmp c2
          return Binary.create(LONG_CMP, y.copyRO(), a.copyRO(), LC(c1+c2));
        }

        else if (def.operator == LONG_NEG) {
          // x = -a; y = x cmp c2
          return Binary.create(LONG_CMP, y.copyRO(), LC(-c2), a.copyRO());
        }
      }
      return null;
    }
    case FLOAT_CMPL_opcode:
    case FLOAT_CMPG_opcode: {
      if (FOLD_FLOATS && FOLD_CMPS) {
        float c2 = getFloatValue(Binary.getVal2(s));
        if (def.operator == FLOAT_ADD) {
          float c1 = getFloatValue(Binary.getVal2(def));
          // x = a + c1; y = x cmp c2
          return Binary.create(s.operator, y.copyRO(), a.copyRO(), FC(c2-c1));
        }
        else if (def.operator == FLOAT_SUB) {
          float c1 = getFloatValue(Binary.getVal2(def));
          // x = a - c1; y = x cmp c2
          return Binary.create(s.operator, y.copyRO(), a.copyRO(), FC(c1+c2));
        }
        else if (def.operator == FLOAT_NEG) {
          // x = -a; y = x cmp c2
          return Binary.create(s.operator, y.copyRO(), FC(-c2), a.copyRO());
        }
      }
      return null;
    }
    case DOUBLE_CMPL_opcode:
    case DOUBLE_CMPG_opcode:  {
      if (FOLD_DOUBLES && FOLD_CMPS) {
        double c2 = getDoubleValue(Binary.getVal2(s));
        if (def.operator == DOUBLE_ADD) {
          double c1 = getDoubleValue(Binary.getVal2(def));
          // x = a + c1; y = x cmp c2
          return Binary.create(s.operator, y.copyRO(), a.copyRO(), DC(c2-c1));
        }
        else if (def.operator == DOUBLE_SUB) {
          double c1 = getDoubleValue(Binary.getVal2(def));
          // x = a - c1; y = x cmp c2
          return Binary.create(s.operator, y.copyRO(), a.copyRO(), DC(c1+c2));
        }
        else if (def.operator == DOUBLE_NEG) {
          // x = -a; y = x cmp c2
          return Binary.create(s.operator, y.copyRO(), DC(-c2), a.copyRO());
        }
      }
      return null;
    }
    case BOOLEAN_CMP_INT_opcode: {
      if (FOLD_INTS && FOLD_CMPS) {
        int c2 = getIntValue(BooleanCmp.getVal2(s));
        OPT_ConditionOperand cond = (OPT_ConditionOperand)BooleanCmp.getCond(s).copy();
        OPT_BranchProfileOperand prof = (OPT_BranchProfileOperand)BooleanCmp.getBranchProfile(s).copy();
        if (def.operator == INT_ADD) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a + c1; y = x cmp c2
          return BooleanCmp.create(BOOLEAN_CMP_INT, y.copyRO(), a.copyRO(), IC(c2-c1), cond, prof);
        }
        else if (def.operator == INT_SUB) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a - c1; y = x cmp c2
          return BooleanCmp.create(BOOLEAN_CMP_INT, y.copyRO(), a.copyRO(), IC(c1+c2), cond, prof);
        }
        else if (def.operator == INT_NEG) {
          // x = -a; y = x cmp c2
          return BooleanCmp.create(BOOLEAN_CMP_INT, y.copyRO(), a.copyRO(), IC(-c2), cond.flipOperands(), prof);
        }
      }
      return null;
    }
    case BOOLEAN_CMP_LONG_opcode: {
      if (FOLD_LONGS && FOLD_CMPS) {
        long c2 = getLongValue(BooleanCmp.getVal2(s));
        OPT_ConditionOperand cond = (OPT_ConditionOperand)BooleanCmp.getCond(s).copy();
        OPT_BranchProfileOperand prof = (OPT_BranchProfileOperand)BooleanCmp.getBranchProfile(s).copy();
        if (def.operator == LONG_ADD) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a + c1; y = x cmp c2
          return BooleanCmp.create(BOOLEAN_CMP_LONG, y.copyRO(), a.copyRO(), LC(c2-c1), cond, prof);
        }
        else if (def.operator == LONG_SUB) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a - c1; y = x cmp c2
          return BooleanCmp.create(BOOLEAN_CMP_LONG, y.copyRO(), a.copyRO(), LC(c1+c2), cond, prof);
        }
        else if (def.operator == LONG_NEG) {
          // x = -a; y = x cmp c2
          return BooleanCmp.create(BOOLEAN_CMP_INT, y.copyRO(), a.copyRO(), LC(-c2), cond.flipOperands(), prof);
        }
      }
      return null;
    }
    case BOOLEAN_CMP_ADDR_opcode: {
      if (FOLD_REFS && FOLD_CMPS) {
        Address c2 = getAddressValue(BooleanCmp.getVal2(s));
        OPT_ConditionOperand cond = (OPT_ConditionOperand)BooleanCmp.getCond(s).copy();
        OPT_BranchProfileOperand prof = (OPT_BranchProfileOperand)BooleanCmp.getBranchProfile(s).copy();
        if (def.operator == REF_ADD) {
          Address c1 = getAddressValue(Binary.getVal2(def));
          // x = a + c1; y = x cmp c2
          return BooleanCmp.create(BOOLEAN_CMP_ADDR, y.copyRO(), a.copyRO(), AC(c2.toWord().minus(c1.toWord()).toAddress()), cond, prof);
        }
        else if (def.operator == REF_SUB) {
          Address c1 = getAddressValue(Binary.getVal2(def));
          // x = a - c1; y = x cmp c2
          return BooleanCmp.create(BOOLEAN_CMP_ADDR, y.copyRO(), a.copyRO(), AC(c1.toWord().plus(c2.toWord()).toAddress()), cond, prof);
        }
        else if (def.operator == REF_NEG) {
          // x = -a; y = x cmp c2
          return BooleanCmp.create(BOOLEAN_CMP_ADDR, y.copyRO(), a.copyRO(), AC(Word.zero().minus(c2.toWord()).toAddress()), cond.flipOperands(), prof);
        }
      }
      return null;
    }
    case INT_IFCMP_opcode: {
      if (FOLD_INTS && FOLD_IFCMPS) {
        int c2 = getIntValue(IfCmp.getVal2(s));
        OPT_ConditionOperand cond = (OPT_ConditionOperand)IfCmp.getCond(s).copy();
        OPT_BranchOperand target = (OPT_BranchOperand)IfCmp.getTarget(s).copy();
        OPT_BranchProfileOperand prof = (OPT_BranchProfileOperand)IfCmp.getBranchProfile(s).copy();
        if (def.operator == INT_ADD) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a + c1; y = x cmp c2
          return IfCmp.create(INT_IFCMP, y.copyRO(), a.copyRO(), IC(c2-c1), cond, target, prof);
        }
        else if (def.operator == INT_SUB) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a - c1; y = x cmp c2
          return IfCmp.create(INT_IFCMP, y.copyRO(), a.copyRO(), IC(c1+c2), cond, target, prof);
        }
        else if (def.operator == INT_NEG) {
          // x = -a; y = x cmp c2
          return IfCmp.create(INT_IFCMP, y.copyRO(), a.copyRO(), IC(-c2), cond.flipOperands(), target, prof);
        }
      }
      return null;
    }
    case LONG_IFCMP_opcode: {
      if (FOLD_LONGS && FOLD_IFCMPS) {
        long c2 = getLongValue(IfCmp.getVal2(s));
        OPT_ConditionOperand cond = (OPT_ConditionOperand)IfCmp.getCond(s).copy();
        OPT_BranchOperand target = (OPT_BranchOperand)IfCmp.getTarget(s).copy();
        OPT_BranchProfileOperand prof = (OPT_BranchProfileOperand)IfCmp.getBranchProfile(s).copy();
        if (def.operator == LONG_ADD) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a + c1; y = x cmp c2
          return IfCmp.create(LONG_IFCMP, y.copyRO(), a.copyRO(), LC(c2-c1), cond, target, prof);
        }
        else if (def.operator == LONG_SUB) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a - c1; y = x cmp c2
          return IfCmp.create(LONG_IFCMP, y.copyRO(), a.copyRO(), LC(c1+c2), cond, target, prof);
        }
        else if (def.operator == LONG_NEG) {
          // x = -a; y = x cmp c2
          return IfCmp.create(LONG_IFCMP, y.copyRO(), a.copyRO(), LC(-c2), cond.flipOperands(), target, prof);
        }
      }
      return null;
    }
    case FLOAT_IFCMP_opcode: {
      if (FOLD_FLOATS && FOLD_IFCMPS) {
        float c2 = getFloatValue(IfCmp.getVal2(s));
        OPT_ConditionOperand cond = (OPT_ConditionOperand)IfCmp.getCond(s).copy();
        OPT_BranchOperand target = (OPT_BranchOperand)IfCmp.getTarget(s).copy();
        OPT_BranchProfileOperand prof = (OPT_BranchProfileOperand)IfCmp.getBranchProfile(s).copy();
        if (def.operator == FLOAT_ADD) {
          float c1 = getFloatValue(Binary.getVal2(def));
          // x = a + c1; y = x cmp c2
          return IfCmp.create(FLOAT_IFCMP, y.copyRO(), a.copyRO(), FC(c2-c1), cond, target, prof);
        }
        else if (def.operator == FLOAT_SUB) {
          float c1 = getFloatValue(Binary.getVal2(def));
          // x = a - c1; y = x cmp c2
          return IfCmp.create(FLOAT_IFCMP, y.copyRO(), a.copyRO(), FC(c1+c2), cond, target, prof);
        }
        else if (def.operator == FLOAT_NEG) {
          // x = -a; y = x cmp c2
          return IfCmp.create(FLOAT_IFCMP, y.copyRO(), a.copyRO(), FC(-c2), cond.flipOperands(), target, prof);
        }
      }
      return null;
    }
    case DOUBLE_IFCMP_opcode: {
      if (FOLD_DOUBLES && FOLD_IFCMPS) {
        double c2 = getDoubleValue(IfCmp.getVal2(s));
        OPT_ConditionOperand cond = (OPT_ConditionOperand)IfCmp.getCond(s).copy();
        OPT_BranchOperand target = (OPT_BranchOperand)IfCmp.getTarget(s).copy();
        OPT_BranchProfileOperand prof = (OPT_BranchProfileOperand)IfCmp.getBranchProfile(s).copy();
        if (def.operator == DOUBLE_ADD) {
          double c1 = getDoubleValue(Binary.getVal2(def));
          // x = a + c1; y = x cmp c2
          return IfCmp.create(DOUBLE_IFCMP, y.copyRO(), a.copyRO(), DC(c2-c1), cond, target, prof);
        }
        else if (def.operator == DOUBLE_SUB) {
          double c1 = getDoubleValue(Binary.getVal2(def));
          // x = a - c1; y = x cmp c2
          return IfCmp.create(DOUBLE_IFCMP, y.copyRO(), a.copyRO(), DC(c1+c2), cond, target, prof);
        }
        else if (def.operator == DOUBLE_NEG) {
          // x = -a; y = x cmp c2
          return IfCmp.create(DOUBLE_IFCMP, y.copyRO(), a.copyRO(), DC(-c2), cond.flipOperands(), target, prof);
        }
      }
      return null;
    }
    case REF_IFCMP_opcode: {
      if (FOLD_REFS && FOLD_IFCMPS) {
        Address c2 = getAddressValue(IfCmp.getVal2(s));
        OPT_ConditionOperand cond = (OPT_ConditionOperand)IfCmp.getCond(s).copy();
        OPT_BranchOperand target = (OPT_BranchOperand)IfCmp.getTarget(s).copy();
        OPT_BranchProfileOperand prof = (OPT_BranchProfileOperand)IfCmp.getBranchProfile(s).copy();
        if (def.operator == REF_ADD) {
          Address c1 = getAddressValue(Binary.getVal2(def));
          // x = a + c1; y = x cmp c2
          return IfCmp.create(REF_IFCMP, y.copyRO(), a.copyRO(), AC(c2.toWord().minus(c1.toWord()).toAddress()), cond, target, prof);
        }
        else if (def.operator == REF_SUB) {
          Address c1 = getAddressValue(Binary.getVal2(def));
          // x = a - c1; y = x cmp c2
          return IfCmp.create(REF_IFCMP, y.copyRO(), a.copyRO(), AC(c1.toWord().plus(c2.toWord()).toAddress()), cond, target, prof);
        }
        else if (def.operator == REF_NEG) {
          // x = -a; y = x cmp c2
          return IfCmp.create(REF_IFCMP, y.copyRO(), a.copyRO(), AC(Word.zero().minus(c2.toWord()).toAddress()), cond.flipOperands(), target, prof);
        }
      }
      return null;
    }
    case INT_IFCMP2_opcode: {
      if (FOLD_INTS && FOLD_IFCMPS) {
        int c2 = getIntValue(IfCmp.getVal2(s));
        OPT_ConditionOperand cond1 = (OPT_ConditionOperand)IfCmp2.getCond1(s).copy();
        OPT_ConditionOperand cond2 = (OPT_ConditionOperand)IfCmp2.getCond2(s).copy();
        OPT_BranchOperand target1 = (OPT_BranchOperand)IfCmp2.getTarget1(s).copy();
        OPT_BranchOperand target2 = (OPT_BranchOperand)IfCmp2.getTarget2(s).copy();
        OPT_BranchProfileOperand prof1 = (OPT_BranchProfileOperand)IfCmp2.getBranchProfile1(s).copy();
        OPT_BranchProfileOperand prof2 = (OPT_BranchProfileOperand)IfCmp2.getBranchProfile2(s).copy();
        if (def.operator == INT_ADD) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a + c1; y = x cmp c2
          return IfCmp2.create(INT_IFCMP2, y.copyRO(), a.copyRO(), IC(c2-c1),
              cond1, target1, prof1, cond2, target2, prof2);
        }
        else if (def.operator == INT_SUB) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a - c1; y = x cmp c2
          return IfCmp2.create(INT_IFCMP2, y.copyRO(), a.copyRO(), IC(c1+c2),
              cond1, target1, prof1, cond2, target2, prof2);
        }
        else if (def.operator == INT_NEG) {
          // x = -a; y = x cmp c2
          return IfCmp2.create(INT_IFCMP2, y.copyRO(), a.copyRO(), IC(-c2),
              cond1.flipOperands(), target1, prof1,
              cond2.flipOperands(), target2, prof2);
        }
      }
      return null;
    }

    case INT_COND_MOVE_opcode:
    case LONG_COND_MOVE_opcode:
    case REF_COND_MOVE_opcode:
    case FLOAT_COND_MOVE_opcode:
    case DOUBLE_COND_MOVE_opcode:
    case GUARD_COND_MOVE_opcode: {
      if (FOLD_INTS && FOLD_CONDMOVES) {
        OPT_Operand trueValue = CondMove.getTrueValue(s);
        OPT_Operand falseValue = CondMove.getFalseValue(s);
        OPT_ConditionOperand cond = (OPT_ConditionOperand)CondMove.getCond(s).copy();
        switch (def.operator.opcode) {
        case INT_ADD_opcode: {
          int c1 = getIntValue(Binary.getVal2(def));
          int c2 = getIntValue(CondMove.getVal2(s));
          // x = a + c1; y = x cmp c2 ? trueValue : falseValue
          return CondMove.create(s.operator(), y.copyRO(), a.copyRO(), IC(c2-c1),
              cond, trueValue, falseValue);
        }
        case LONG_ADD_opcode: {
          long c1 = getLongValue(Binary.getVal2(def));
          long c2 = getLongValue(CondMove.getVal2(s));
          // x = a + c1; y = x cmp c2 ? trueValue : falseValue
          return CondMove.create(s.operator(), y.copyRO(), a.copyRO(), LC(c2-c1),
              cond, trueValue, falseValue);
        }
        case REF_ADD_opcode: {
          Address c1 = getAddressValue(Binary.getVal2(def));
          Address c2 = getAddressValue(CondMove.getVal2(s));
          // x = a + c1; y = x cmp c2 ? trueValue : falseValue
          return CondMove.create(s.operator(), y.copyRO(), a.copyRO(), AC(c2.toWord().minus(c1.toWord()).toAddress()),
              cond, trueValue, falseValue);
        }
        case FLOAT_ADD_opcode: {
          float c1 = getFloatValue(Binary.getVal2(def));
          float c2 = getFloatValue(CondMove.getVal2(s));
          // x = a + c1; y = x cmp c2 ? trueValue : falseValue
          return CondMove.create(s.operator(), y.copyRO(), a.copyRO(), FC(c2-c1),
              cond, trueValue, falseValue);
        }
        case DOUBLE_ADD_opcode: {
          double c1 = getDoubleValue(Binary.getVal2(def));
          double c2 = getDoubleValue(CondMove.getVal2(s));
          // x = a + c1; y = x cmp c2 ? trueValue : falseValue
          return CondMove.create(s.operator(), y.copyRO(), a.copyRO(), DC(c2-c1),
              cond, trueValue, falseValue);
        }
        case INT_SUB_opcode: {
          int c1 = getIntValue(Binary.getVal2(def));
          int c2 = getIntValue(CondMove.getVal2(s));
          // x = a - c1; y = x cmp c2 ? trueValue : falseValue
          return CondMove.create(s.operator(), y.copyRO(), a.copyRO(), IC(c1+c2),
              cond, trueValue, falseValue);
        }
        case LONG_SUB_opcode: {
          long c1 = getLongValue(Binary.getVal2(def));
          long c2 = getLongValue(CondMove.getVal2(s));
          // x = a - c1; y = x cmp c2 ? trueValue : falseValue
          return CondMove.create(s.operator(), y.copyRO(), a.copyRO(), LC(c1+c2),
              cond, trueValue, falseValue);
        }
        case REF_SUB_opcode: {
          Address c1 = getAddressValue(Binary.getVal2(def));
          Address c2 = getAddressValue(CondMove.getVal2(s));
          // x = a - c1; y = x cmp c2 ? trueValue : falseValue
          return CondMove.create(s.operator(), y.copyRO(), a.copyRO(), AC(c1.toWord().plus(c2.toWord()).toAddress()),
              cond, trueValue, falseValue);
        }
        case FLOAT_SUB_opcode: {
          float c1 = getFloatValue(Binary.getVal2(def));
          float c2 = getFloatValue(CondMove.getVal2(s));
          // x = a - c1; y = x cmp c2 ? trueValue : falseValue
          return CondMove.create(s.operator(), y.copyRO(), a.copyRO(), FC(c1+c2),
              cond, trueValue, falseValue);
        }
        case DOUBLE_SUB_opcode: {
          double c1 = getDoubleValue(Binary.getVal2(def));
          double c2 = getDoubleValue(CondMove.getVal2(s));
          // x = a - c1; y = x cmp c2 ? trueValue : falseValue
          return CondMove.create(s.operator(), y.copyRO(), a.copyRO(), DC(c1+c2),
              cond, trueValue, falseValue);
        }
        case INT_NEG_opcode: {
          int c2 = getIntValue(CondMove.getVal2(s));
          // x = -a; y = x cmp c2 ? trueValue : falseValue
          return CondMove.create(s.operator(), y.copyRO(), a.copyRO(), IC(-c2),
              cond.flipOperands(), trueValue, falseValue);
        }
        case LONG_NEG_opcode: {
          long c2 = getLongValue(CondMove.getVal2(s));
          // x = -a; y = x cmp c2 ? trueValue : falseValue
          return CondMove.create(s.operator(), y.copyRO(), a.copyRO(), LC(-c2),
              cond.flipOperands(), trueValue, falseValue);
        }
        case REF_NEG_opcode: {
          Address c2 = getAddressValue(CondMove.getVal2(s));
          // x = -a; y = x cmp c2 ? trueValue : falseValue
          return CondMove.create(s.operator(), y.copyRO(), a.copyRO(), AC(Word.zero().minus(c2.toWord()).toAddress()),
              cond.flipOperands(), trueValue, falseValue);
        }
        case FLOAT_NEG_opcode: {
          float c2 = getFloatValue(CondMove.getVal2(s));
          // x = -a; y = x cmp c2 ? trueValue : falseValue
          return CondMove.create(s.operator(), y.copyRO(), a.copyRO(), FC(-c2),
              cond.flipOperands(), trueValue, falseValue);
        }
        case DOUBLE_NEG_opcode: {
          double c2 = getDoubleValue(CondMove.getVal2(s));
          // x = -a; y = x cmp c2 ? trueValue : falseValue
          return CondMove.create(s.operator(), y.copyRO(), a.copyRO(), DC(-c2),
              cond.flipOperands(), trueValue, falseValue);
        }
        default:
        }
      }
      return null;
    }

    case INT_NEG_opcode: {
      if (FOLD_INTS && FOLD_NEGS) {
        if (def.operator == INT_NEG) {
          // x = -z; y = -x;
          return Move.create(INT_MOVE, y.copyRO(), Unary.getVal(def));
        }
        else if (def.operator == INT_MUL) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a * c1; y = -x;
          return Binary.create(INT_MUL, y.copyRO(), a.copyRO(), IC(-c1));
        }
        else if (def.operator == INT_DIV) {
          int c1 = getIntValue(GuardedBinary.getVal2(def));
          OPT_Operand guard = GuardedBinary.getGuard(def);
          // x = a / c1; y = -x;
          return GuardedBinary.create(INT_DIV, y.copyRO(), a.copyRO(), IC(-c1), guard);
        }
        else if (FOLD_CONSTANTS_TO_LHS && (def.operator == INT_ADD)) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a + c1; y = -x;
          return Binary.create(INT_SUB, y.copyRO(), IC(-c1), a.copyRO());
        }
        else if (FOLD_CONSTANTS_TO_LHS && (def.operator == INT_SUB)) {
          int c1 = getIntValue(Binary.getVal2(def));
          // x = a - c1; y = -x;
          return Binary.create(INT_SUB, y.copyRO(), IC(c1), a.copyRO());
        }
      }
      return null;
    }

    case REF_NEG_opcode: {
      if (FOLD_REFS && FOLD_NEGS) {
        if (def.operator == REF_NEG) {
          // x = -z; y = -x;
          return Move.create(REF_MOVE, y.copyRO(), Unary.getVal(def));
        }
        else if (FOLD_CONSTANTS_TO_LHS && (def.operator == REF_ADD)) {
          Address c1 = getAddressValue(Binary.getVal2(def));
          // x = a + c1; y = -x;
          return Binary.create(REF_SUB, y.copyRO(), AC(Word.zero().minus(c1.toWord()).toAddress()), a.copyRO());
        }
        else if (FOLD_CONSTANTS_TO_LHS && (def.operator == REF_SUB)) {
          Address c1 = getAddressValue(Binary.getVal2(def));
          // x = a - c1; y = -x;
          return Binary.create(REF_SUB, y.copyRO(), AC(c1), a.copyRO());
        }
      }
      return null;
    }

    case LONG_NEG_opcode: {
      if (FOLD_LONGS && FOLD_NEGS) {
        if (def.operator == LONG_NEG) {
          // x = -z; y = -x;
          return Move.create(LONG_MOVE, y.copyRO(), Unary.getVal(def));
        }
        else if (def.operator == LONG_MUL) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a * c1; y = -x;
          return Binary.create(LONG_MUL, y.copyRO(), a.copyRO(), LC(-c1));
        }
        else if (def.operator == LONG_DIV) {
          long c1 = getLongValue(GuardedBinary.getVal2(def));
          OPT_Operand guard = GuardedBinary.getGuard(def);
          // x = a / c1; y = -x;
          return GuardedBinary.create(LONG_DIV, y.copyRO(), a.copyRO(), LC(-c1), guard);
        }
        else if (FOLD_CONSTANTS_TO_LHS && (def.operator == LONG_ADD)) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a + c1; y = -x;
          return Binary.create(LONG_SUB, y.copyRO(), LC(-c1), a.copyRO());
        }
        else if (FOLD_CONSTANTS_TO_LHS && (def.operator == LONG_SUB)) {
          long c1 = getLongValue(Binary.getVal2(def));
          // x = a - c1; y = -x;
          return Binary.create(LONG_SUB, y.copyRO(), LC(c1), a.copyRO());
        }
      }
      return null;
    }

    case FLOAT_NEG_opcode: {
      if (FOLD_FLOATS && FOLD_NEGS) {
        if (def.operator == FLOAT_NEG) {
          // x = -z; y = -x;
          return Move.create(FLOAT_MOVE, y.copyRO(), Unary.getVal(def));
        }
        else if (def.operator == FLOAT_MUL) {
          float c1 = getFloatValue(Binary.getVal2(def));
          // x = a * c1; y = -x;
          return Binary.create(FLOAT_MUL, y.copyRO(), a.copyRO(), FC(-c1));
        }
        else if (def.operator == FLOAT_DIV) {
          float c1 = getFloatValue(Binary.getVal2(def));
          // x = a / c1; y = -x;
          return Binary.create(FLOAT_DIV, y.copyRO(), a.copyRO(), FC(-c1));
        }
        else if (FOLD_CONSTANTS_TO_LHS && (def.operator == FLOAT_ADD)) {
          float c1 = getFloatValue(Binary.getVal2(def));
          // x = a + c1; y = -x;
          return Binary.create(FLOAT_SUB, y.copyRO(), FC(-c1), a.copyRO());
        }
        else if (FOLD_CONSTANTS_TO_LHS && (def.operator == FLOAT_SUB)) {
          float c1 = getFloatValue(Binary.getVal2(def));
          // x = a - c1; y = -x;
          return Binary.create(FLOAT_SUB, y.copyRO(), FC(c1), a.copyRO());
        }
      }
      return null;
    }

    case DOUBLE_NEG_opcode: {
      if (FOLD_DOUBLES && FOLD_NEGS) {
        if (def.operator == DOUBLE_NEG) {
          // x = -z; y = -x;
          return Move.create(DOUBLE_MOVE, y.copyRO(), Unary.getVal(def));
        }
        else if (def.operator == DOUBLE_MUL) {
          double c1 = getDoubleValue(Binary.getVal2(def));
          // x = a * c1; y = -x;
          return Binary.create(DOUBLE_MUL, y.copyRO(), a.copyRO(), DC(-c1));
        }
        else if (def.operator == DOUBLE_DIV) {
          double c1 = getDoubleValue(Binary.getVal2(def));
          // x = a / c1; y = -x;
          return Binary.create(DOUBLE_DIV, y.copyRO(), a.copyRO(), DC(-c1));
        }
        else if (FOLD_CONSTANTS_TO_LHS && (def.operator == DOUBLE_ADD)) {
          double c1 = getDoubleValue(Binary.getVal2(def));
          // x = a + c1; y = -x;
          return Binary.create(DOUBLE_SUB, y.copyRO(), DC(-c1), a.copyRO());
        }
        else if (FOLD_CONSTANTS_TO_LHS && (def.operator == DOUBLE_SUB)) {
          double c1 = getDoubleValue(Binary.getVal2(def));
          // x = a - c1; y = -x;
          return Binary.create(DOUBLE_SUB, y.copyRO(), DC(c1), a.copyRO());
        }
      }
      return null;
    }

    case INT_NOT_opcode: {
      if (FOLD_INTS && FOLD_NOTS) {
        if (def.operator == INT_NOT) {
          // x = -1 ^ z; y = -1 ^ x;
          return Move.create(INT_MOVE, y.copyRO(), Unary.getVal(def));
        }
      }
      return null;
    }

    case REF_NOT_opcode: {
      if (FOLD_REFS && FOLD_NOTS) {
        if (def.operator == REF_NOT) {
          // x = -1 ^ z; y = -1 ^ x;
          return Move.create(REF_MOVE, y.copyRO(), Unary.getVal(def));
        }
      }
      return null;
    }


    case LONG_NOT_opcode: {
      if (FOLD_LONGS && FOLD_NOTS) {
        if (def.operator == LONG_NOT) {
          // x = -1 ^ z; y = -1 ^ x;
          return Move.create(LONG_MOVE, y.copyRO(), Unary.getVal(def));
        }
      }
      return null;
    }

    case INT_2BYTE_opcode: {
      if (FOLD_INTS && FOLD_2CONVERSION) {
        if ((def.operator == INT_2BYTE) ||
            (def.operator == INT_2SHORT)) {
          // x = (short)z; y = (byte)x;
          return Unary.create(INT_2BYTE, y.copyRO(), Unary.getVal(def));
        }
        else if (def.operator == INT_2USHORT) {
          // x = (char)z; y = (byte)x;
          return Binary.create(INT_AND, y.copyRO(), Unary.getVal(def), IC(0xFF));
        }        
      }
      return null;
    }
    case INT_2SHORT_opcode: {
      if (FOLD_INTS && FOLD_2CONVERSION) {
        if (def.operator == INT_2BYTE) {
          // x = (byte)z; y = (short)x;
          return Unary.create(INT_2BYTE, y.copyRO(), Unary.getVal(def));        
        }
        else if (def.operator == INT_2SHORT) {
          // x = (short)z; y = (short)x;
          return Unary.create(INT_2SHORT, y.copyRO(), Unary.getVal(def));
        }
        else if (def.operator == INT_2USHORT) {
          // x = (char)z; y = (short)x;
          return Unary.create(INT_2USHORT, y.copyRO(), Unary.getVal(def));
        }        
      }
      return null;
    }
    case INT_2USHORT_opcode: {
      if (FOLD_INTS && FOLD_2CONVERSION) {
        if ((def.operator == INT_2SHORT)||
            (def.operator == INT_2USHORT)) {
          // x = (short)z; y = (char)x;
          return Unary.create(INT_2USHORT, y.copyRO(), Unary.getVal(def));
        }
      }
      return null;
    }

    case LONG_2INT_opcode: {
      if (FOLD_LONGS && FOLD_2CONVERSION) {
        if (def.operator == INT_2LONG) {
          // x = (long)z; y = (int)x;
          return Move.create(INT_MOVE, y.copyRO(), Unary.getVal(def));
        }
      }
      return null;
    }
    case INT_2LONG_opcode:
      // unused
      return null;

    case DOUBLE_2FLOAT_opcode: {
      if (FOLD_DOUBLES && FOLD_2CONVERSION) {
        if (def.operator == FLOAT_2DOUBLE) {
          // x = (double)z; y = (float)x;
          return Move.create(FLOAT_MOVE, y.copyRO(), Unary.getVal(def));
        }
      }
      return null;      
    }
      
    case FLOAT_2DOUBLE_opcode:
      // unused
      return null;

    default:
      OPT_OptimizingCompilerException.UNREACHABLE();
    return null;
    }
  }

  /**
   * Does instruction s compute a register r = candidate expression?
   * 
   * @return the computed register, or null
   */
  private static OPT_Register isCandidateExpression(OPT_Instruction s) {

    switch (s.operator.opcode) {
    // Foldable operators
    case INT_NOT_opcode:
    case REF_NOT_opcode:
    case LONG_NOT_opcode:

    case INT_NEG_opcode:
    case REF_NEG_opcode:
    case LONG_NEG_opcode:
    case FLOAT_NEG_opcode:
    case DOUBLE_NEG_opcode:

    case INT_2BYTE_opcode:
    case INT_2SHORT_opcode:
    case INT_2USHORT_opcode:
    case INT_2LONG_opcode:
    case LONG_2INT_opcode:
    case FLOAT_2DOUBLE_opcode:
    case DOUBLE_2FLOAT_opcode: {
      OPT_Operand val1 = Unary.getVal(s);
      // if val1 is constant too, this should've been constant folded
      // beforehand. Give up.
      if (val1.isConstant())
        return null;
      return Unary.getResult(s).asRegister().register;
    }

    case INT_ADD_opcode:
    case REF_ADD_opcode:
    case LONG_ADD_opcode:
    case FLOAT_ADD_opcode:
    case DOUBLE_ADD_opcode:

    case INT_SUB_opcode:
    case REF_SUB_opcode:
    case LONG_SUB_opcode:
    case FLOAT_SUB_opcode:
    case DOUBLE_SUB_opcode:

    case INT_MUL_opcode:
    case LONG_MUL_opcode:
    case FLOAT_MUL_opcode:
    case DOUBLE_MUL_opcode:

    case FLOAT_DIV_opcode:
    case DOUBLE_DIV_opcode:

    case INT_SHL_opcode:
    case REF_SHL_opcode:
    case LONG_SHL_opcode:

    case INT_SHR_opcode:
    case REF_SHR_opcode:
    case LONG_SHR_opcode:

    case INT_USHR_opcode:
    case REF_USHR_opcode:
    case LONG_USHR_opcode:

    case INT_AND_opcode:
    case REF_AND_opcode:
    case LONG_AND_opcode:

    case INT_OR_opcode:
    case REF_OR_opcode:
    case LONG_OR_opcode:

    case INT_XOR_opcode:
    case REF_XOR_opcode:
    case LONG_XOR_opcode:

    case LONG_CMP_opcode:
    case FLOAT_CMPL_opcode:
    case DOUBLE_CMPL_opcode:
    case FLOAT_CMPG_opcode:
    case DOUBLE_CMPG_opcode: {

      OPT_Operand val2 = Binary.getVal2(s);
      if (!val2.isObjectConstant()) {
        if (val2.isConstant()) {
          OPT_Operand val1 = Binary.getVal1(s);
          // if val1 is constant too, this should've been constant folded
          // beforehand. Give up.
          if (val1.isConstant())
            return null;

          return Binary.getResult(s).asRegister().register;
        } else {
          if (VM.VerifyAssertions)
            VM._assert(val2.isRegister());

          OPT_Operand val1 = Binary.getVal1(s);
          if (s.operator.isCommutative() && val1.isConstant()
              && !val1.isObjectConstant()) {
            Binary.setVal1(s, Binary.getClearVal2(s));
            Binary.setVal2(s, val1);
            return Binary.getResult(s).asRegister().register;
          }
        }
      }
      return null;
    }

    case INT_DIV_opcode:
    case LONG_DIV_opcode: {
      OPT_Operand val2 = GuardedBinary.getVal2(s);
      if (val2.isConstant()) {
        OPT_Operand val1 = GuardedBinary.getVal1(s);
        // if val1 is constant too, this should've been constant folded
        // beforehand. Give up.
        if (val1.isConstant())
          return null;

        return GuardedBinary.getResult(s).asRegister().register;
      }
      return null;
    }

    case BOOLEAN_CMP_INT_opcode:
    case BOOLEAN_CMP_LONG_opcode:
    case BOOLEAN_CMP_ADDR_opcode: {
      OPT_Operand val2 = BooleanCmp.getVal2(s);
      if (!val2.isObjectConstant()) {
        if (val2.isConstant()) {
          OPT_Operand val1 = BooleanCmp.getVal1(s);
          // if val1 is constant too, this should've been constant folded
          // beforehand. Give up.
          if (val1.isConstant())
            return null;

          return BooleanCmp.getResult(s).asRegister().register;
        } else {
          if (VM.VerifyAssertions)
            VM._assert(val2.isRegister());
          OPT_Operand val1 = BooleanCmp.getVal1(s);
          if (val1.isConstant() && !val1.isObjectConstant()) {
            BooleanCmp.setVal1(s, BooleanCmp.getClearVal2(s));
            BooleanCmp.setVal2(s, val1);
            BooleanCmp.getCond(s).flipOperands();
            return BooleanCmp.getResult(s).asRegister().register;
          }
        }
      }
      return null;
    }
    case INT_IFCMP_opcode:
    case LONG_IFCMP_opcode:
    case FLOAT_IFCMP_opcode:
    case DOUBLE_IFCMP_opcode:
    case REF_IFCMP_opcode: {
      OPT_Operand val2 = IfCmp.getVal2(s);
      if (!val2.isObjectConstant()) {
        if (val2.isConstant()) {
          OPT_Operand val1 = IfCmp.getVal1(s);
          // if val1 is constant too, this should've been constant folded
          // beforehand. Give up.
          if (val1.isConstant())
            return null;

          return IfCmp.getGuardResult(s).asRegister().register;
        } else {
          if (VM.VerifyAssertions)
            VM._assert(val2.isRegister());
          OPT_Operand val1 = IfCmp.getVal1(s);
          if (val1.isConstant() && !val1.isObjectConstant()) {
            IfCmp.setVal1(s, IfCmp.getClearVal2(s));
            IfCmp.setVal2(s, val1);
            IfCmp.getCond(s).flipOperands();
            return IfCmp.getGuardResult(s).asRegister().register;
          }
        }
      }
      return null;
    }
    case INT_IFCMP2_opcode: {
      OPT_Operand val2 = IfCmp2.getVal2(s);
      if (!val2.isObjectConstant()) {
        if (val2.isConstant()) {
          OPT_Operand val1 = IfCmp2.getVal1(s);
          // if val1 is constant too, this should've been constant folded
          // beforehand. Give up.
          if (val1.isConstant())
            return null;

          return IfCmp2.getGuardResult(s).asRegister().register;
        } else {
          if (VM.VerifyAssertions)
            VM._assert(val2.isRegister());
          OPT_Operand val1 = IfCmp2.getVal1(s);
          if (val1.isConstant() && !val1.isObjectConstant()) {
            IfCmp2.setVal1(s, IfCmp2.getClearVal2(s));
            IfCmp2.setVal2(s, val1);
            IfCmp2.getCond1(s).flipOperands();
            IfCmp2.getCond2(s).flipOperands();
            return IfCmp2.getGuardResult(s).asRegister().register;
          }
        }
      }
      return null;
    }
    case INT_COND_MOVE_opcode:
    case LONG_COND_MOVE_opcode:
    case REF_COND_MOVE_opcode:
    case FLOAT_COND_MOVE_opcode:
    case DOUBLE_COND_MOVE_opcode:
    case GUARD_COND_MOVE_opcode: {
      OPT_Operand val2 = CondMove.getVal2(s);
      if (!val2.isObjectConstant()) {
        if (val2.isConstant()) {
          OPT_Operand val1 = CondMove.getVal1(s);
          // if val1 is constant too, this should've been constant folded
          // beforehand. Give up.
          if (val1.isConstant())
            return null;

          return CondMove.getResult(s).asRegister().register;
        } else {
          if (VM.VerifyAssertions)
            VM._assert(val2.isRegister());
          OPT_Operand val1 = CondMove.getVal1(s);
          if (val1.isConstant() && !val1.isObjectConstant()) {
            CondMove.setVal1(s, CondMove.getClearVal2(s));
            CondMove.setVal2(s, val1);
            CondMove.getCond(s).flipOperands();
            return CondMove.getResult(s).asRegister().register;
          }
        }
      }
      return null;
    }
    default:
      // Operator can't be folded
      return null;
    }
  }

  private static int getIntValue(OPT_Operand op) {
    if (op instanceof OPT_IntConstantOperand)
      return op.asIntConstant().value;
    throw new OPT_OptimizingCompilerException(
        "Cannot getLongValue from this operand " + op);
  }

  private static long getLongValue(OPT_Operand op) {
    if (op instanceof OPT_LongConstantOperand)
      return op.asLongConstant().value;
    throw new OPT_OptimizingCompilerException(
        "Cannot getLongValue from this operand " + op);
  }

  private static float getFloatValue(OPT_Operand op) {
    if (op instanceof OPT_FloatConstantOperand)
      return op.asFloatConstant().value;
    throw new OPT_OptimizingCompilerException(
        "Cannot getFloatValue from this operand " + op);
  }

  private static double getDoubleValue(OPT_Operand op) {
    if (op instanceof OPT_DoubleConstantOperand)
      return op.asDoubleConstant().value;
    throw new OPT_OptimizingCompilerException(
        "Cannot getDoubleValue from this operand " + op);
  }

  private static Address getAddressValue(OPT_Operand op) {
    if (op instanceof OPT_NullConstantOperand)
      return Address.zero();
    if (op instanceof OPT_AddressConstantOperand)
      return op.asAddressConstant().value;
    if (op instanceof OPT_IntConstantOperand)
      return Address.fromIntSignExtend(op.asIntConstant().value);
    if (VM.BuildFor64Addr && op instanceof OPT_LongConstantOperand)
      return Address.fromLong(op.asLongConstant().value);
    throw new OPT_OptimizingCompilerException(
        "Cannot getWordValue from this operand " + op);
  }
}
