/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 */

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_Interface;
import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;

import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_Memory;
import com.ibm.JikesRVM.VM_Word;
import com.ibm.JikesRVM.VM_Magic;
import com.ibm.JikesRVM.VM_PragmaInline;
import com.ibm.JikesRVM.VM_PragmaNoInline;
import com.ibm.JikesRVM.VM_PragmaUninterruptible;
import com.ibm.JikesRVM.VM_Uninterruptible;

/**
 * This abstract class implements core functionality for a generic
 * large object allocator. The shared VMResource used by each instance
 * is the point of global synchronization, and synchronization only
 * occurs at the granularity of aquiring (and releasing) chunks of
 * memory from the VMResource.  Subclasses may require finer grained
 * synchronization during a marking phase, for example.<p>
 *
 * This is a first cut implementation, with plenty of room for
 * improvement...
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */
abstract class LargeObjectAllocator implements Constants, VM_Uninterruptible {
  public final static String Id = "$Id$"; 
  
  ////////////////////////////////////////////////////////////////////////////
  //
  // Class variables
  //
  protected static final VM_Word PAGE_MASK = VM_Word.fromInt(~(PAGE_SIZE - 1));

  ////////////////////////////////////////////////////////////////////////////
  //
  // Instance variables
  //
  protected FreeListVMResource vmResource;
  protected MemoryResource memoryResource;

  ////////////////////////////////////////////////////////////////////////////
  //
  // Initialization
  //

  /**
   * Constructor
   *
   * @param vmr The virtual memory resource from which this free list
   * allocator will acquire virtual memory.
   * @param mr The memory resource against which memory consumption
   * for this free list allocator will be accounted.
   */
  LargeObjectAllocator(FreeListVMResource vmr, MemoryResource mr) {
    vmResource = vmr;
    memoryResource = mr;
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Allocation
  //

  /**
   * Allocate space for a new object
   *
   * @param isScalar Is the object to be allocated a scalar (or array)?
   * @param bytes The number of bytes allocated
   * @return The address of the first byte of the allocated cell
   */
  public final VM_Address alloc(boolean isScalar, EXTENT bytes) 
    throws VM_PragmaInline {
    return alloc(isScalar, bytes, false);
  }

  /**
   * Allocate space for an object
   *
   * @param isScalar Is the object to be allocated a scalar (or array)?
   * @param bytes The number of bytes allocated
   * @param copy Is this object being copied (or is it a regular allocation?)
   * @return The address of the first byte of the allocated cell Will
   * not return zero.
   */
  private final VM_Address alloc(boolean isScalar, EXTENT bytes, boolean copy) 
    throws VM_PragmaInline {
    VM_Address cell;
    for (int count = 0; ; count++) {
      cell = allocLarge(isScalar, bytes);
      if (!cell.isZero()) break;
      VM_Interface.getPlan().poll(true, memoryResource);
      if (count > 2) VM.sysFail("Out of memory in LargeObejectAllocator.alloc");
    }
    postAlloc(cell);
    Memory.zero(cell, bytes);
    return cell;
  }

  /**
   * Allocate space for a copied object
   *
   * @param isScalar Is the object to be allocated a scalar (or array)?
   * @param bytes The number of bytes allocated
   * @return The address of the first byte of the allocated cell
   */
  public final VM_Address allocCopy(boolean isScalar, EXTENT bytes) 
    throws VM_PragmaInline {
    return alloc(isScalar, bytes, true);
  }

  abstract protected void postAlloc(VM_Address cell);
    
  /**
   * Allocate a large object.  Large objects are directly allocted and
   * freed in page-grained units via the vm resource.  This routine
   * does not guarantee that the space will have been zeroed.  The
   * caller must explicitly zero the space.
   *
   * @param isScalar True if the object to occupy this space will be a scalar.
   * @param bytes The required size of this space in bytes.
   * @return The address of the start of the newly allocated region at
   * least <code>bytes</code> bytes in size.
   */
  private final VM_Address allocLarge(boolean isScalar, EXTENT bytes) {
    int header = superPageHeaderSize() + cellHeaderSize();
    bytes += header;
    int pages = (bytes + PAGE_SIZE - 1)>>LOG_PAGE_SIZE;
    VM_Address sp = allocSuperPage(pages);
    if (sp.isZero()) return sp;
    return sp.add(header);
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Freeing
  //

  /**
   * Free a cell.  If the cell is large (own superpage) then release
   * the superpage, if not add to the super page's free list and if
   * all cells on the superpage are free, then release the
   * superpage.
   *
   * @param cell The address of the first byte of the cell to be freed
   * @param sp The superpage containing the cell
   * @param sizeClass The sizeclass of the cell.
   */
  protected final void free(VM_Address cell)
    throws VM_PragmaInline {
    freeSuperPage(getSuperPage(cell));
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Superpages
  //

  abstract protected int superPageHeaderSize();
  abstract protected int cellHeaderSize();

  /**
   * Allocate a super page.
   *
   * @param pages The size of the superpage in pages.
   * @return The address of the first word of the superpage.  May return zero.
   */
  private final VM_Address allocSuperPage(int pages) {
    return vmResource.acquire(pages, memoryResource);
  }

  /**
   * Return a superpage to the global page pool by freeing it with the
   * vm resource.  Before this is done the super page is unlinked from
   * the linked list of super pages for this free list
   * instance.
   *
   * @param sp The superpage to be freed.
   */
  protected final void freeSuperPage(VM_Address sp) {
    vmResource.release(sp, memoryResource);
  }

  /**
   * Return the superpage for a given cell.  If the cell is a small
   * cell then this is found by masking the cell address to find the
   * containing page.  Otherwise the first word of the cell contains
   * the address of the page.
   *
   * @param cell The address of the first word of the cell (exclusive
   * of any sub-class specific metadata).
   * @param small True if the cell is a small cell (single page superpage).
   * @return The address of the first word of the superpage containing
   * <code>cell</code>.
   */
  public static final VM_Address getSuperPage(VM_Address cell)
    throws VM_PragmaInline {
    return cell.toWord().and(PAGE_MASK).toAddress();
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Miscellaneous
  //
  public void show() {
  }
}

