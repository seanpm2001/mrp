/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2003
 */
package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_Interface;
import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;

import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_AddressArray;
import com.ibm.JikesRVM.VM_Offset;
import com.ibm.JikesRVM.VM_Word;
import com.ibm.JikesRVM.VM_Magic;
import com.ibm.JikesRVM.VM_PragmaInline;
import com.ibm.JikesRVM.VM_PragmaNoInline;
import com.ibm.JikesRVM.VM_PragmaUninterruptible;
import com.ibm.JikesRVM.VM_Uninterruptible;

/**
 *
 * This abstract class implements a simple segregated free list.<p>
 *
 * See: Wilson, Johnstone, Neely and Boles "Dynamic Storage
 * Allocation: A Survey and Critical Review", IWMM 1995, for an
 * overview of free list allocation and the various implementation
 * strategies, including segregated free lists.<p>
 *
 * We maintain a number of size classes, each size class having a free
 * list of available objects of that size (the list may be empty).  We
 * call the storage elements "cells".  Cells reside within chunks of
 * memory called "blocks".  All cells in a given block are of the same
 * size (i.e. blocks are homogeneous with respect to size class).
 * Each block maintains its own free list (free cells within that
 * block).  For each size class a list of blocks is maintained, one of
 * which will serve the role of the current free list.  When the free
 * list on the current block is exhausted, the next block for that
 * size class becomes the current block and its free list is used.  If
 * there are no more blocks the a new block is allocated.<p>
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */
abstract class SegregatedFreeList extends Allocator 
  implements Constants, VM_Uninterruptible {
  public final static String Id = "$Id$"; 

  ////////////////////////////////////////////////////////////////////////////
  //
  // Class variables
  //
  protected static final VM_Address DEBUG_BLOCK = VM_Address.fromInt(0xffffffff);  // 0x5b098008
  protected static final int SIZE_CLASSES = 40;
  protected static final int FREE_LIST_HEADER_BYTES = WORD_SIZE;
  private static final int FREE_LIST_OFFSET = 0;
  private static final int FREE_LIST_BITS = BlockAllocator.MAX_BLOCK_LOG;
  private static final int SIZE_CLASS_BITS = 6;
  private static final int INUSE_BITS = 10;
  private static final int SIZE_CLASS_SHIFT = FREE_LIST_BITS;
  private static final int INUSE_SHIFT = FREE_LIST_BITS + SIZE_CLASS_BITS;
  protected static final int MIN_CELLS = 6;
  protected static final int MAX_CELLS = 99; //(1<<(INUSE_BITS-1))-1;
  private static final int FREE_LIST_MASK = (1<<FREE_LIST_BITS)-1;
  private static final int SIZE_CLASS_MASK = ((1<<SIZE_CLASS_BITS)-1)<<SIZE_CLASS_SHIFT;
  private static final int INUSE_MASK = ((1<<INUSE_BITS)-1)<<INUSE_SHIFT;

  protected static int[] cellSize;
  protected static byte[] blockSizeClass;
  protected static int[] blockHeaderSize;
  protected static int[] cellsInBlock;

  public static final boolean FRAGMENTATION_CHECK = false;
  protected static final boolean FRAG_VERBOSE = false;
  protected static int bytesAlloc;
  private long[] fragInuseCellBytes;
  private int[] fragUsedPages;

  ////////////////////////////////////////////////////////////////////////////
  //
  // Instance variables
  //
  protected BlockAllocator blockAllocator;
  protected VM_AddressArray freeList; 
  protected VM_AddressArray firstBlock;
  protected VM_AddressArray lastBlock;
  protected VM_AddressArray currentBlock;
  protected int [] cellsInUse;

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
   * @param plan The plan with which this instance is associated.
   */
  SegregatedFreeList(FreeListVMResource vmr, MemoryResource mr, Plan plan) {
    blockAllocator = new BlockAllocator(vmr, mr, plan);
    freeList = VM_AddressArray.create(SIZE_CLASSES);
    firstBlock = VM_AddressArray.create(SIZE_CLASSES);
    lastBlock = VM_AddressArray.create(SIZE_CLASSES);
    currentBlock = VM_AddressArray.create(SIZE_CLASSES);
    cellsInUse = new int[SIZE_CLASSES];
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Allocation
  //

  /**
   * Allocate <code>bytes</code> contigious bytes of zeroed memory.<p>
   *
   * This code first tries the fast version and, if needed, the slow path.
   *
   * @param isScalar True if the object to occupy this space will be a
   * scalar.
   * @param bytes The size of the object to occupy this space, in bytes.
   * @return The address of the first word of <code>bytes</code>
   * contigious bytes of zeroed memory.
   */
  public final VM_Address alloc (boolean isScalar, EXTENT bytes) 
    throws VM_PragmaInline {
    if (FRAGMENTATION_CHECK)
      bytesAlloc += bytes;
    VM_Address cell = allocFast(isScalar, bytes);
    if (cell.isZero()) 
      return allocSlow(isScalar, bytes);
    else
      return cell;
  }

  /**
   * Allocate <code>bytes</code> contigious bytes of zeroed memory.<p>
   *
   * This code must be efficient and must compile easily.  Here we
   * minimize the number of calls to inlined functions, and force the
   * "slow path" (uncommon case) out of line to reduce pressure on the
   * compiler.  We have a call to an abstract method that allows
   * subclasses to customize post-allocation.
   *
   * @param isScalar True if the object to occupy this space will be a
   * scalar.
   * @param bytes The size of the object to occupy this space, in bytes.
   * @return The address of the first word of <code>bytes</code>
   * contigious bytes of zeroed memory.
   */
  public final VM_Address allocFast (boolean isScalar, EXTENT bytes) 
    throws VM_PragmaInline {

    int sizeClass = getSizeClass(bytes);
    VM_Address cell = freeList.get(sizeClass);
    if (!cell.isZero()) {
      cellsInUse[sizeClass]++;
      freeList.set(sizeClass, getNextCell(cell));
      postAlloc(cell, currentBlock.get(sizeClass), sizeClass, bytes);
      Memory.zeroSmall(cell, bytes);
    } 
    return cell;

  }

  abstract void postAlloc(VM_Address cell, VM_Address block, int sizeClass,
			  EXTENT bytes);

  /**
   * Allocate <code>bytes</code> contigious bytes of non-zeroed memory.
   * First check if the fast path works.  This is needed since this method
   * may be called in the context when the fast version was NOT just called.
   * If this fails, it will try finding another block with a non-empty free list, 
   * or allocating a new block.<p>
   *
   * This code should be relatively infrequently executed, so it is
   * forced out of line to reduce pressure on the compilation of the
   * core alloc routine.<p>
   *
   * Precondition: None 
   *
   * Postconditions: A new cell has been allocated (not zeroed), and
   * the block containing the cell has been placed on the appropriate
   * free list data structures.  The free list itself is not updated
   * (the caller must do so).<p>
   * 
   * @param isScalar True if the object to occupy this space will be a
   * scalar.
   * @param bytes The size of the object to occupy this space, in bytes.
   * @param sizeClass  The size class of the cell to be allocated.
   * @return The address of the first word of the <code>bytes</code>
   * contigious bytes of zerod memory.
   */
   public final VM_Address allocSlowOnce (boolean isScalar, int bytes) throws VM_PragmaNoInline {


    VM_Address cell = allocFast(isScalar, bytes);
    if (!cell.isZero()) 
	return cell;

    int sizeClass = getSizeClass(bytes);
    VM_Address current = currentBlock.get(sizeClass);
    if (!current.isZero()) {
      // flush the current (empty) free list
      flushFreeList(current, sizeClass, VM_Address.zero());

      // find a free list which is not empty
      current = BlockAllocator.getNextBlock(current);
      while (!current.isZero()) {
	advanceToBlock(current, sizeClass);
	cell = getFreeList(current);
	if (!cell.isZero()) {
	  // this block has at least one free cell, so use it
	  currentBlock.set(sizeClass, current);
	  cellsInUse[sizeClass] = getInUse(current) + 1;
	  freeList.set(sizeClass, getNextCell(cell));
	  postAlloc(cell, currentBlock.get(sizeClass),
		    sizeClass, bytes);
	  Memory.zeroSmall(cell, bytes);
	  return cell;
	}
	current = BlockAllocator.getNextBlock(current);
      }
    }

    cell = expandSizeClass(sizeClass);
    if (cell.isZero())
      return VM_Address.zero();

    cellsInUse[sizeClass]++;
    freeList.set(sizeClass, getNextCell(cell));
    postAlloc(cell, currentBlock.get(sizeClass), sizeClass, bytes);
    Memory.zeroSmall(cell, bytes);
    return cell;
  }

  abstract protected void advanceToBlock(VM_Address block, int sizeClass);

  /**
   * Expand a particular size class, allocating a new block, breaking
   * the block into cells and placing those cells on a free list for
   * that block.  The block becomes the current head for this size
   * class and the address of the first available cell is returned.
   *
   * @param sizeClass The size class to be expanded
   * @return The address of the first available cell in the newly
   * allocated block, or zero if there were insufficient resources to
   * allocate a new block.
   */
  private final VM_Address expandSizeClass(int sizeClass) 
    throws VM_PragmaInline {
    VM_Address block = blockAllocator.alloc(blockSizeClass[sizeClass]);
    if (block.isZero())
      return VM_Address.zero();

    installNewBlock(block, sizeClass);

    int cellExtent = cellSize[sizeClass];
    VM_Address cursor = block.add(blockHeaderSize[sizeClass]);
    VM_Address sentinal = block.add(BlockAllocator.blockSize(blockSizeClass[sizeClass]));
    VM_Address lastCell = VM_Address.zero();
    int cellCount = 0;
    while (cursor.add(cellExtent).LE(sentinal)) {
      setNextCell(cursor, lastCell); 
      lastCell = cursor;
      cursor = cursor.add(cellExtent);
      cellCount++;
    }
    cellsInUse[sizeClass] = 0;
    setSizeClass(block, sizeClass);
    postExpandSizeClass(block, sizeClass);
    
    if (VM.VerifyAssertions)
      VM._assert(!lastCell.isZero());
    return lastCell;
  }

  abstract void postExpandSizeClass(VM_Address block, int sizeClass);

  /**
   * Return the next cell in a free list chain.
   *
   * @param cell The address of teh current cell.
   * @return The next cell in a free list chain (null if this is the
   * last).
   */
  protected final VM_Address getNextCell(VM_Address cell)
    throws VM_PragmaInline {
    return VM_Magic.getMemoryAddress(cell);
  }

  /**
   * Set the next cell in a free list chain
   *
   * @param cell The cell whose link is to be set
   * @param next The next cell in the chain.
   */
  private final void setNextCell(VM_Address cell, VM_Address next)
    throws VM_PragmaInline {
    VM_Magic.setMemoryAddress(cell, next);
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Freeing
  //

  /**
   * Free a cell.  The cell is added to the free list for the given
   * block, and the inuse count for the block is decremented, the
   * block freed if empty.
   *
   * @param cell The cell to be freed
   * @param block The block on which the cell resides
   * @param sizeClass The size class of the cell and block
   */
  protected final void free(VM_Address cell, VM_Address block, int sizeClass)
    throws VM_PragmaInline {
    addToFreeList(cell, block);
    if (decInUse(block) == 0)
      freeBlock(block, sizeClass);
  }

  /**
   * Add a cell to its block's free list.
   * 
   * @param cell The cell to be added to the free list
   * @param block The block on which the cell resides
   */
  private final void addToFreeList(VM_Address cell, VM_Address block)
    throws VM_PragmaInline {
    VM_Address next = getFreeList(block);
    setNextCell(cell, next);
    setFreeList(block, cell);
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Block management
  //

  /**
   * Install a new block. The block needs to be added to the size
   * class's linked list of blocks and made the current block.
   *
   * @param block The block to be added
   * @param sizeClass The size class to which the block is being added
   */
  private final void installNewBlock(VM_Address block, int sizeClass) 
    throws VM_PragmaInline {
    BlockAllocator.linkedListInsert(block, lastBlock.get(sizeClass));
    currentBlock.set(sizeClass, block);
    lastBlock.set(sizeClass, block);
    if (firstBlock.get(sizeClass).isZero())
      firstBlock.set(sizeClass, block);
  }

  /**
   * Free a block.  The block needs to be removed from its size
   * class's linked list before being freed.
   *
   * @param block The block to be freed
   * @param sizeClass The size class with which the block was associated.
   */
  protected final void freeBlock(VM_Address block, int sizeClass) 
    throws VM_PragmaInline {
    VM_Address next = BlockAllocator.getNextBlock(block);
    VM_Address prev = BlockAllocator.getPrevBlock(block);
    BlockAllocator.unlinkBlock(block);
    if (firstBlock.get(sizeClass).EQ(block))
      firstBlock.set(sizeClass, next);
    if (currentBlock.get(sizeClass).EQ(block))
      currentBlock.set(sizeClass, next);
    if (lastBlock.get(sizeClass).EQ(block))
      lastBlock.set(sizeClass, prev);
    blockAllocator.free(block, blockSizeClass[sizeClass]);
  }


  ////////////////////////////////////////////////////////////////////////////
  //
  // Size classes
  //

  /**
   * Get the size class for a given number of bytes.<p>
   *
   * We use size classes based on a worst case internal fragmentation
   * loss target of 1/8.  In fact, across sizes from 8 bytes to 512
   * the average worst case loss is 13.3%, giving an expected loss
   * (assuming uniform distribution) of about 7%.  We avoid using the
   * Lea class sizes because they were so numerous and therefore
   * liable to lead to excessive inter-class-size fragmentation.<p>
   * 
   * This method may segregate arrays and scalars (currently it does
   * not).
   *
   * @param isScalar True if the object to occupy the allocated space
   * will be a scalar (i.e. not a array).
   * @param bytes The number of bytes required to accommodate the
   * object to be allocated.
   * @return The size class capable of accomodating the allocation
   * request.  If the request is sufficiently large then
   * <code>LARGE_SIZE_CLASS</code> will be returned, indicating that
   * the request will not be satisfied by the freelist, but must be
   * dealt with explicitly as a large object.
   */
  protected static final int getSizeClass(EXTENT bytes)
    throws VM_PragmaInline {
    if (VM.VerifyAssertions) VM._assert((bytes > 0) && (bytes <= 8192));

    int sz1 = bytes - 1;
    int offset = 0;
    return ((sz1 <=   63) ?      (sz1 >>  2): //    4 bytes apart
	    (sz1 <=  127) ? 12 + (sz1 >>  4): //   16 bytes apart
	    (sz1 <=  255) ? 16 + (sz1 >>  5): //   32 bytes apart
	    (sz1 <=  511) ? 20 + (sz1 >>  6): //   64 bytes apart
	    (sz1 <= 2047) ? 26 + (sz1 >>  8): //  256 bytes apart
	                    32 + (sz1 >> 10));// 1024 bytes apart
  }

  /**
   * Return the size of a basic cell (i.e. not including any cell
   * header) for a given size class.
   *
   * @param sc The size class in question
   * @return The size of a basic cell (i.e. not including any cell
   * header).
   */
  protected static final int getBaseCellSize(int sc) 
    throws VM_PragmaInline {
    if (VM.VerifyAssertions) VM._assert((sc >= 0) && (sc < SIZE_CLASSES));

    return ((sc < 16) ? (sc +  1) <<  2:
	    (sc < 19) ? (sc - 11) <<  4:
	    (sc < 23) ? (sc - 15) <<  5:
	    (sc < 27) ? (sc - 19) <<  6:
	    (sc < 33) ? (sc - 25) <<  8:
	                (sc - 31) << 10);
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Misc
  //

  public final void flushFreeLists() {
    for (int sizeClass = 0; sizeClass < SIZE_CLASSES; sizeClass++)
      if (!currentBlock.get(sizeClass).isZero()) {
	VM_Address block = currentBlock.get(sizeClass);
	VM_Address cell = freeList.get(sizeClass);
	flushFreeList(block, sizeClass, cell);
	currentBlock.set(sizeClass, VM_Address.zero());
	freeList.set(sizeClass, VM_Address.zero());
      }
  }

  public final void restoreFreeLists() {
    for (int sizeClass = 0; sizeClass < SIZE_CLASSES; sizeClass++) {
      currentBlock.set(sizeClass, firstBlock.get(sizeClass));
      VM_Address block = firstBlock.get(sizeClass);
      if (block.isZero()) {
	freeList.set(sizeClass, VM_Address.zero());
	cellsInUse[sizeClass] = 0;
      } else {
	freeList.set(sizeClass, getFreeList(block));
	cellsInUse[sizeClass] = getInUse(block);
      }
    }
  }

  private final void flushFreeList(VM_Address block, int sizeClass, 
				   VM_Address cell) 
    throws VM_PragmaInline {
    setFreeListSizeClassAndInUse(block, cell, sizeClass, cellsInUse[sizeClass]);
  }

  private final void refreshFreeLists() {
    for (int sizeClass = 0; sizeClass < SIZE_CLASSES; sizeClass++)
      currentBlock.set(sizeClass, firstBlock.get(sizeClass));
  }

  private final void setFreeListSizeClassAndInUse(VM_Address block,
						  VM_Address cell,
						  int sizeClass, int inuse) 
    throws VM_PragmaInline {
    VM_Word value = VM_Word.fromInt((cell.toInt() & FREE_LIST_MASK) | (sizeClass << SIZE_CLASS_SHIFT) | (inuse << INUSE_SHIFT));
    VM_Magic.setMemoryWord(block.add(FREE_LIST_OFFSET), value);
    
    if (VM.VerifyAssertions) {
      VM._assert(inuse == getInUse(block));
      VM._assert(cell == getFreeList(block));
      VM._assert(sizeClass == getSizeClass(block));
    }
  }

  protected static final VM_Address getFreeList(VM_Address block) 
    throws VM_PragmaInline {
    VM_Word value = VM_Magic.getMemoryWord(block.add(FREE_LIST_OFFSET));
    value = value.and(VM_Word.fromInt(FREE_LIST_MASK));
    if (value.isZero())
      return VM_Address.zero();
    else
      return value.or(block.toWord().and(VM_Word.fromInt(~FREE_LIST_MASK))).toAddress();
  }

  private static final void setFreeList(VM_Address block, VM_Address cell)
    throws VM_PragmaInline {
    int value = VM_Magic.getMemoryInt(block.add(FREE_LIST_OFFSET));
    value = (cell.toInt() & FREE_LIST_MASK) | (value & ~FREE_LIST_MASK);
    VM_Magic.setMemoryInt(block.add(FREE_LIST_OFFSET), value);
  }

  protected static final int getSizeClass(VM_Address block) 
    throws VM_PragmaInline {
    int value = VM_Magic.getMemoryInt(block.add(FREE_LIST_OFFSET));
    value = (value & SIZE_CLASS_MASK)>>SIZE_CLASS_SHIFT;
    return value;
  }

  private static final void setSizeClass(VM_Address block, int sizeClass)
    throws VM_PragmaInline {
    int value = VM_Magic.getMemoryInt(block.add(FREE_LIST_OFFSET));
    value = (value & ~SIZE_CLASS_MASK) | (sizeClass<<SIZE_CLASS_SHIFT);
    VM_Magic.setMemoryInt(block.add(FREE_LIST_OFFSET), value);
  }

  protected static final int getInUse(VM_Address block) 
    throws VM_PragmaInline {
    int value = VM_Magic.getMemoryInt(block.add(FREE_LIST_OFFSET));
    return value >> INUSE_SHIFT;
  }

  private static final void setInUse(VM_Address block, int inuse) 
    throws VM_PragmaInline {
    int value = VM_Magic.getMemoryInt(block.add(FREE_LIST_OFFSET));
    value = (value & ~INUSE_MASK) | (inuse << INUSE_SHIFT);
    VM_Magic.setMemoryInt(block.add(FREE_LIST_OFFSET), value);
  }

  private static final int decInUse(VM_Address block) 
    throws VM_PragmaInline {
    
    int value = VM_Magic.getMemoryInt(block.add(FREE_LIST_OFFSET));
    value -= (1<<INUSE_SHIFT);
    VM_Magic.setMemoryInt(block.add(FREE_LIST_OFFSET), value);
    int count = value>>INUSE_SHIFT;
    if (VM.VerifyAssertions) {
      VM._assert(count >= 0 && count < (1<<INUSE_BITS));
    }
    return count;
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Miscellaneous
  //
  public void show() {
  }
}
