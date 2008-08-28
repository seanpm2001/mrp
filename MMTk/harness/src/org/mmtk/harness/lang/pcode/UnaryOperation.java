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

import org.mmtk.harness.lang.Env;
import org.mmtk.harness.lang.ast.Operator;
import org.mmtk.harness.lang.compiler.Register;
import org.mmtk.harness.lang.runtime.StackFrame;

public final class UnaryOperation extends UnaryOp {

  public final Operator op;

  public UnaryOperation(Register resultTemp, Register operand, Operator op) {
    super(op.toString(), resultTemp, operand);
    this.op = op;
  }

  @Override
  public void exec(Env env) {
    StackFrame frame = env.top();
    setResult(frame, op.operate(frame.get(operand)));
  }
}
