/*
 * This file is part of the Jikes RVM project (http://jikesrvm.sourceforge.net).
 * The Jikes RVM project is distributed under the Common Public License (CPL).
 * A copy of the license is included in the distribution, and is also
 * available at http://www.opensource.org/licenses/cpl1.0.php
 *
 * (C) Copyright IBM Corp. 2001
 */
//$Id:&

/**
 * Represent a trace record as a user defined exit RVM record.
 *
 * @author Peter F. Sweeney
 * @date 6/4/2003
 */

public class TraceExitRecord extends TraceRecord
{
  /*
   * Fields
   */ 
  // virtual processor id
  public int vpid = 0;
  // appliction name
  public int value = 0;

  /**
   * Constructor
   *
   * @param app_name  name of application that is started
   */
  TraceExitRecord(int vpid, int value) {
    this.vpid  = vpid;
    this.value = value;
  }
  /**
   * print trace record
   */
  public boolean print()
  {
    System.out.println("VP "+vpid+" Exit RVM "+value);
    return true;
  }
}