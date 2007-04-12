/*
 * This file is part of binarytranslator.org. The binarytranslator.org
 * project is distributed under the Common Public License (CPL).
 * A copy of the license is included in the distribution, and is also
 * available at http://www.opensource.org/licenses/cpl1.0.php
 *
 * (C) Copyright Ian Rogers, The University of Manchester 2003-2006
 */
package org.binarytranslator;

import java.util.HashMap;
import java.util.Vector;
import java.util.Map.Entry;

/**
 * Options for controlling the emulator
 */
public class DBT_Options {
  // -oO Runtime settings Oo-
  
  /**
   * Debug binary loading
   */
  public final static boolean debugLoader = true;

  /**
   * Are unimplemented system calls are fatal?
   */
  public final static boolean unimplementedSystemCallsFatal = true;

  // -oO Translation settings Oo-
  
  /** The file that is currently being executed. */
  public static String executableFile;
  
  /** Arguments given to the executable.*/
  public static String[] executableArguments = null;

  /**
   * The initial optimisation level
   */
  public static int initialOptLevel = 0;

  /**
   * Instructions to translate for an optimisation level 0 trace
   */
  public static int instrOpt0 = 684;

  /**
   * Instructions to translate for an optimisation level 1 trace
   */
  public static int instrOpt1 = 1500;

  /**
   * Instructions to translate for an optimisation level 2 trace
   */
  public static int instrOpt2 = 1500;

  /**
   * Favour backward branch optimization. Translate backward branch addresses
   * before the next instructions (this is the manner of the 601's branch
   * predictor).
   */
  public final static boolean optimizeBackwardBranches = true;

  /**
   * Set this to true to record uncaught branch instructions
   */
  public static boolean plantUncaughtBranchWatcher = false;

  /**
   * Should all branches (excluding to lr and ctr) be resolved in one big go or
   * one at at a time
   */
  public static boolean resolveBranchesAtOnce = true;

  /**
   * Should procedures (branches to ctr and lr) be given precedent over more
   * local branches
   */
  public static boolean resolveProceduresBeforeBranches = true;

  /**
   * Use global branch information rather than local (within the trace)
   * information when optimisation level is greater than or equal to this value
   */
  public static int globalBranchLevel = 3;

  /**
   * Set this to true to translate only one instruction at a time.
   */
  public static boolean singleInstrTranslation = true;

  /**
   * Eliminate unneeded filling of register
   */
  public final static boolean eliminateRegisterFills = true;

  // -oO Translation debugging options Oo-

  /**
   * Print dissassembly of translated instructions.
   */
  public static boolean debugInstr = true;

  /**
   * In PPC2IR, print information about lazy resolution...
   */
  public final static boolean debugLazy = false;

  /**
   * In PPC2IR, print cfg.
   */
  public final static boolean debugCFG = false;

  // -oO Runtime debugging options Oo-

  /**
   * Debug using GDB?
   */
  public static boolean gdbStub = false;

  /**
   * GDB stub port
   */
  public static int gdbStubPort = 1234;

  /**
   * In ProcessSpace, print syscall numbers.
   */
  public static boolean debugSyscall = false;

  /**
   * In ProcessSpace, print syscall numbers.
   */
  public static boolean debugSyscallMore = false;

  /**
   * Print out various messages about the emulator starting.
   */
  public static boolean debugRuntime = true;

  /**
   * Print out messages from the memory system
   */
  public static boolean debugMemory = false;

  /**
   * Print out process space between instructions
   */
  public final static boolean debugPS = false;

  /**
   * When printing process space, omit floating point registers.
   */
  public final static boolean debugPS_OmitFP = false;

  /**
   * The user ID for the user running the command
   */
  public final static int UID = 1000;

  /**
   * The group ID for the user running the command
   */
  public final static int GID = 100;
  
  /** Stores the arguments given to the DBT by the user. These are NOT the arguments given to the executable. */
  private final static HashMap<String, String> dbtArguments = new HashMap<String, String>();
  
  /**
   * Read and parse the command line arguments. 
   */
  public static void parseArguments(String[] args) {

    try {
      Vector<String> remainingArguments = new Vector<String>();
      ArgumentParser.parse(args, dbtArguments, remainingArguments);
      
      //did the user give an executable to execute?
      if (remainingArguments.size() > 0) {
        executableFile = remainingArguments.get(0);
        remainingArguments.remove(0);
        
        executableArguments = new String[remainingArguments.size()];
        remainingArguments.toArray(executableArguments);
      }
    }
    catch (ArgumentParser.ParseException e) {
      throw new Error(e.getMessage());
    }
    
    for (Entry<String, String> argument : dbtArguments.entrySet()) {
      String arg = argument.getKey();
      String value = argument.getValue();
      
      try {
        parseSingleArgument(arg, value);
      }
      catch (NumberFormatException e) {
        throw new Error("Argument " + arg + " is not a valid integer.");
      }
    }
  }
  
  /**
   * Parses a single argument into the options class.
   */
  private static void parseSingleArgument(String arg, String value) {

    if (arg.equalsIgnoreCase("-X:dbt:debugInstr")) {
      debugInstr = Boolean.parseBoolean(value);
    } else if (arg.equalsIgnoreCase("-X:dbt:debugRuntime")) {
      debugRuntime = Boolean.parseBoolean(value);
    } else if (arg.equalsIgnoreCase("-X:dbt:debugSyscall")) {
      debugSyscall = Boolean.parseBoolean(value);
    } else if (arg.equalsIgnoreCase("-X:dbt:debugSyscallMore")) {
      debugSyscallMore = Boolean.parseBoolean(value);
    } else if (arg.equalsIgnoreCase("-X:dbt:globalBranchLevel")) {
      globalBranchLevel = Integer.parseInt(value);
    } else if (arg.equalsIgnoreCase("-X:dbt:initialOptLevel")) {
      initialOptLevel = Integer.parseInt(value);
    } else if (arg.equalsIgnoreCase("-X:dbt:instrOpt0")) {
      instrOpt0 = Integer.parseInt(value);
    } else if (arg.equalsIgnoreCase("-X:dbt:instrOpt1")) {
      instrOpt1 = Integer.parseInt(value);
    } else if (arg.equalsIgnoreCase("-X:dbt:instrOpt2")) {
      instrOpt2 = Integer.parseInt(value);
    } else if (arg.equalsIgnoreCase("-X:dbt:singleInstrTranslation")) {
      singleInstrTranslation = Boolean.parseBoolean(value);
    } else if (arg.equalsIgnoreCase("-X:dbt:resolveBranchesAtOnce")) {
      resolveBranchesAtOnce = Boolean.parseBoolean(value);
    } else if (arg.equalsIgnoreCase("-X:dbt:resolveBranchesAtOnce")) {
      resolveBranchesAtOnce = Boolean.parseBoolean(value);
    } else if (arg.equalsIgnoreCase("-X:dbt:resolveProceduresBeforeBranches")) {
      resolveProceduresBeforeBranches = Boolean.parseBoolean(value);
    } else if (arg.equalsIgnoreCase("-X:dbt:resolveProceduresBeforeBranches")) {
      resolveProceduresBeforeBranches = Boolean.parseBoolean(value);
    } else if (arg.equalsIgnoreCase("-X:dbt:gdbStub")) {
      gdbStub = Boolean.parseBoolean(value);
    } else if (arg.equalsIgnoreCase("-X:dbt:gdbStubPort")) {
      gdbStubPort = Integer.parseInt(value);
    } else {
      throw new Error("DBT Options: Unknown emulator option " + arg);
    }
  }
  
  private static class ArgumentParser {
    
    protected State state;
    protected final HashMap<String, String> arguments;
    protected final Vector<String> remainingArguments;
    
    public static ArgumentParser parse(String[] args, HashMap<String, String> keyValueArguments, Vector<String> remainingArguments) 
      throws ParseException {
      
      ArgumentParser parser = new ArgumentParser(keyValueArguments, remainingArguments);
      parser.parseArguments(args);
      return parser;
    }
    
    private ArgumentParser(HashMap<String, String> arguments, Vector<String> remainingArguments) {
      this.arguments = arguments;
      this.remainingArguments = remainingArguments;
    }
    
    private void parseArguments(String[] args) 
      throws ParseException {  
      switchState(new AwaitingKeyState());
      
      int next = 0;
      
      while (next < args.length) {
        String input = args[next++].trim();
        
        int pos = input.indexOf("=");
        
        if (pos == 0) {
          //this token has the form "=TEXT"
          do {
            state.onAssignment();
            input = input.substring(1);
          }
          while (input.startsWith("="));
        }
        else if (pos > 0) {
          //the token has the form "TEXT="
          state.onText(input.substring(0, pos));
          state.onAssignment();
          
          //handle remaining text (form TEXT=TEXT)
          input = input.substring(pos + 1);
        }
        
        if (input.length() > 1) {
          state.onText(input);
        }
      }
      
      state.onEnd();
    }
    
    protected void switchState(State s) {
      state = s;
    }
    
    public static class ParseException extends Exception {
      
      protected ParseException(String msg) {
        super(msg);
      }
    }
    
    private interface State {
      void onText(String text) throws ParseException;
      void onAssignment() throws ParseException;
      void onEnd() throws ParseException;
    }
    
    private final class AwaitingKeyState implements State {

      public void onAssignment() throws ParseException {
        throw new ParseException("Unexpected token '=' while parsing arguments.");
      }

      public void onEnd() throws ParseException {
        //no further arguments, stop parsing
      }

      public void onText(String text) throws ParseException {
        switchState(new AwaitingAssignmentState(text));
      }
    }
    
    private final class AwaitingAssignmentState implements State {
      
      private final String previousInput;
      
      public AwaitingAssignmentState(String previousInput) {
        this.previousInput = previousInput;
      }

      public void onAssignment() throws ParseException {
        switchState(new ParseValueArgumentState(previousInput));
      }

      public void onEnd() throws ParseException {
        //the key has obviously been a single remaining argument
        remainingArguments.add(previousInput);
      }

      public void onText(String text) throws ParseException {
        //the key has obviously been a single remaining argument and now we received the next one
        remainingArguments.add(previousInput);
        remainingArguments.add(text);
        
        switchState(new ParseRemainingArgumentsState());
      }
    }
    
    private final class ParseValueArgumentState implements State {
      
      private final String previousInput;
      
      public ParseValueArgumentState(String previousInput) {
        this.previousInput = previousInput;
      }

      public void onAssignment() throws ParseException {
        throw new ParseException("Invalid value for argument '" + previousInput + "'.");
      }

      public void onEnd() throws ParseException {
        throw new ParseException("Missing value for argument '" + previousInput + "'.");
      }

      public void onText(String text) throws ParseException {
        if (arguments.containsKey(text)) {
          throw new ParseException("Duplicate argument '" + previousInput + "' while parsing arguments.");
        }
        
        arguments.put(previousInput, text);
        switchState(new AwaitingKeyState());
      }
    }
    
    private final class ParseRemainingArgumentsState implements State {

      public void onAssignment() throws ParseException {
        remainingArguments.add("=");
      }

      public void onEnd() throws ParseException {
        //no-op
      }

      public void onText(String text) throws ParseException {
        remainingArguments.add(text);
      }
    }
  }
}
