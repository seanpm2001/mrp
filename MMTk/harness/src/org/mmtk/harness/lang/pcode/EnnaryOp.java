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
package org.mmtk.harness.lang.pcode;

import java.util.List;

import org.mmtk.harness.lang.compiler.Register;
import org.mmtk.harness.lang.runtime.StackFrame;
import org.mmtk.harness.lang.runtime.Value;

public abstract class EnnaryOp extends PseudoOp {

  protected final int[] ops;

  public EnnaryOp(String name, Register resultTemp, List<Register> ops) {
    this(name,resultTemp,ops.toArray(new Register[0]));
  }

  public EnnaryOp(String name, Register resultTemp, Register...ops) {
    super(ops.length, name, resultTemp);
    this.ops = new int[ops.length];
    for (int i=0; i < ops.length; i++) {
      this.ops[i] = ops[i].getIndex();
    }
  }

  public EnnaryOp(String name, List<Register> ops) {
    this(name,ops.toArray(new Register[0]));
  }

  public EnnaryOp(String name, Register...ops) {
    super(ops.length, name);
    this.ops = new int[ops.length];
    for (int i=0; i < ops.length; i++) {
      this.ops[i] = ops[i].getIndex();
    }
  }

  @Override
  public String toString() {
    StringBuilder buf = new StringBuilder(String.format("%s(",super.toString()));
    for (int i=0; i < ops.length; i++) {
      buf.append("t"+ops[i]);
      if (i != ops.length - 1) {
        buf.append(",");
      }
    }
    buf.append(")");
    return buf.toString();
  }

  public Value[] getOperandValues(StackFrame frame) {
    Value[] actuals = new Value[arity];
    for (int i=0; i < arity; i++) {
      actuals[i] = frame.get(ops[i]);
    }
    return actuals;
  }
}
