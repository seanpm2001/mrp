/*
 * This file is part of MMTk (http://jikesrvm.sourceforge.net).
 * MMTk is distributed under the Common Public License (CPL).
 * A copy of the license is included in the distribution, and is also
 * available at http://www.opensource.org/licenses/cpl1.0.php
 *
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2004
 */
package org.mmtk.utility.options;

/**
 * GCspy Tile Size.
 * 
 *
 */
public class GCspyTileSize extends IntOption {
  /**
   * Create the option.
   */
  public GCspyTileSize() {
    super("GCspy Tile Size",
          "GCspy Tile Size",
          131072);
  }

  /**
   * Ensure the tile size is positive
   */
  protected void validate() {
    failIf(this.value <= 0, "Unreasonable gcspy tilesize");
  }
}
