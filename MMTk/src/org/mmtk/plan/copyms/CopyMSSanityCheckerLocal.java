/*
 * This file is part of MMTk (http://jikesrvm.sourceforge.net).
 * MMTk is distributed under the Common Public License (CPL).
 * A copy of the license is included in the distribution, and is also
 * available at http://www.opensource.org/licenses/cpl1.0.php
 *
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2005
 */
package org.mmtk.plan.copyms;

import org.mmtk.policy.Space;

import org.mmtk.utility.sanitychecker.SanityChecker;
import org.mmtk.utility.sanitychecker.SanityCheckerLocal;

import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.*;

/**
 * This class performs sanity checks for CopyMS collectors.
 * 
 * $Id$
 * 
 * @author Daniel Frampton
 * @version $Revision$
 * @date $Date$
 */
@Uninterruptible public class CopyMSSanityCheckerLocal extends SanityCheckerLocal {

  /**
   * Return the expected reference count. For non-reference counting 
   * collectors this becomes a true/false relationship.
   * 
   * @param object The object to check.
   * @param sanityRootRC The number of root references to the object.
   * @return The expected (root excluded) reference count.
   */
  protected int sanityExpectedRC(ObjectReference object, 
                                           int sanityRootRC) {
    Space space = Space.getSpaceForObject(object);

    // Nursery
    if (space == CopyMS.nurserySpace) {
      return global().preGCSanity() 
        ? SanityChecker.UNSURE
        : SanityChecker.DEAD;
    }

    return space.isReachable(object) 
      ? SanityChecker.ALIVE 
      : SanityChecker.DEAD;
  }

}
