/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.mmtk.policy;

import static org.mmtk.utility.Constants.LOG_BYTES_IN_PAGE;

import org.mmtk.plan.TransitiveClosure;
import org.mmtk.utility.heap.FreeListPageResource;
import org.mmtk.utility.heap.VMRequest;
import org.mmtk.utility.HeaderByte;
import org.mmtk.utility.Treadmill;

import org.mmtk.vm.VM;

import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.*;

/**
 * Each instance of this class corresponds to one explicitly managed
 * large object space.
 */
@Uninterruptible
public final class LargeObjectSpace extends BaseLargeObjectSpace {

  /****************************************************************************
   *
   * Class variables
   */

  /**
   *
   */
  public static final int LOCAL_GC_BITS_REQUIRED = 2;
  public static final int GLOBAL_GC_BITS_REQUIRED = 0;
  private static final byte MARK_BIT =     1; // ...01
  private static final byte NURSERY_BIT =  2; // ...10
  private static final byte LOS_BIT_MASK = 3; // ...11

  /****************************************************************************
   *
   * Instance variables
   */

  /**
   *
   */
  private byte markState;
  private boolean inNurseryGC;
  private final Treadmill treadmill;

  /****************************************************************************
   *
   * Initialization
   */

  /**
   * The caller specifies the region of virtual memory to be used for
   * this space.  If this region conflicts with an existing space,
   * then the constructor will fail.
   *
   * @param name The name of this space (used when printing error messages etc)
   * @param vmRequest An object describing the virtual memory requested.
   */
  public LargeObjectSpace(String name, VMRequest vmRequest) {
    this(name, true, vmRequest);
  }

  /**
   * The caller specifies the region of virtual memory to be used for
   * this space.  If this region conflicts with an existing space,
   * then the constructor will fail.
   *
   * @param name The name of this space (used when printing error messages etc)
   * @param zeroed if true, allocations return zeroed memory.
   * @param vmRequest An object describing the virtual memory requested.
   */
  public LargeObjectSpace(String name, boolean zeroed, VMRequest vmRequest) {
    super(name, zeroed, vmRequest);
    treadmill = new Treadmill(LOG_BYTES_IN_PAGE, true);
    markState = 0;
  }

  /****************************************************************************
   *
   * Collection
   */

  /**
   * Prepares for a new collection increment.  For the mark-sweep
   * collector we must flip the state of the mark bit between
   * collections.
   *
   * @param fullHeap whether the collection will be full heap
   */
  public void prepare(boolean fullHeap) {
    if (fullHeap) {
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(treadmill.fromSpaceEmpty());
      }
      markState = (byte) (MARK_BIT - markState);
    }
    treadmill.flip(fullHeap);
    inNurseryGC = !fullHeap;
  }

  /**
   * A new collection increment has completed.  For the mark-sweep
   * collector this means we can perform the sweep phase.
   *
   * @param fullHeap whether the collection was full heap
   */
  public void release(boolean fullHeap) {
    // sweep the large objects
    sweepLargePages(true);                // sweep the nursery
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(treadmill.nurseryEmpty());
    if (fullHeap) sweepLargePages(false); // sweep the mature space
  }

  /**
   * Sweeps through the large pages, releasing all superpages on the
   * "from space" treadmill.
   *
   * @param sweepNursery whether to sweep the nursery
   */
  private void sweepLargePages(boolean sweepNursery) {
    while (true) {
      Address cell = sweepNursery ? treadmill.popNursery() : treadmill.pop();
      if (cell.isZero()) break;
      release(getSuperPage(cell));
    }
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(sweepNursery ? treadmill.nurseryEmpty() : treadmill.fromSpaceEmpty());
  }

  @Override
  @Inline
  public void release(Address first) {
    ((FreeListPageResource) pr).releasePages(first);
  }

  /****************************************************************************
   *
   * Object processing and tracing
   */

  /**
   * Trace a reference to an object under a mark sweep collection
   * policy.  If the object header is not already marked, mark the
   * object in either the bitmap or by moving it off the treadmill,
   * and enqueue the object for subsequent processing. The object is
   * marked as (an atomic) side-effect of checking whether already
   * marked.
   *
   * @param trace The nurseryTrace being conducted.
   * @param object The object to be traced.
   * @return The object (there is no object forwarding in this
   * collector, so we always return the same object: this could be a
   * void method but for compliance to a more general interface).
   */
  @Override
  @Inline
  public ObjectReference traceObject(TransitiveClosure trace, ObjectReference object) {
    boolean nurseryObject = isInNursery(object);
    if (!inNurseryGC || nurseryObject) {
      if (testAndMark(object, markState)) {
        internalMarkObject(object, nurseryObject);
        trace.processNode(object);
      }
    }
    return object;
  }

  /**
   * @param object The object in question
   * @return {@code true} if this object is known to be live (i.e. it is marked)
   */
  @Override
  @Inline
   public boolean isLive(ObjectReference object) {
    return testMarkBit(object, markState);
  }

  /**
   * An object has been marked (identifiged as live).  Large objects
   * are added to the to-space treadmill, while all other objects will
   * have a mark bit set in the superpage header.
   *
   * @param object The object which has been marked.
   * @param nurseryObject whether the object is in the nursery
   */
  @Inline
  private void internalMarkObject(ObjectReference object, boolean nurseryObject) {

    Address cell = VM.objectModel.objectStartRef(object);
    Address node = Treadmill.midPayloadToNode(cell);
    treadmill.copy(node, nurseryObject);
  }

  /****************************************************************************
   *
   * Header manipulation
   */

  /**
   * Perform any required initialization of the GC portion of the header.
   *
   * @param object the object ref to the storage to be initialized
   * @param alloc is this initialization occurring due to (initial) allocation
   * ({@code true}) or due to copying ({@code false})?
   */
  @Inline
  public void initializeHeader(ObjectReference object, boolean alloc) {
    byte oldValue = VM.objectModel.readAvailableByte(object);
    byte newValue = (byte) ((oldValue & ~LOS_BIT_MASK) | markState);
    if (alloc) newValue |= NURSERY_BIT;
    if (HeaderByte.NEEDS_UNLOGGED_BIT) newValue |= HeaderByte.UNLOGGED_BIT;
    VM.objectModel.writeAvailableByte(object, newValue);
    Address cell = VM.objectModel.objectStartRef(object);
    treadmill.addToTreadmill(Treadmill.midPayloadToNode(cell), alloc);
  }

  /**
   * Atomically attempt to set the mark bit of an object.
   *
   * @param object The object whose mark bit is to be written
   * @param value The value to which the mark bit will be set
   * @return {@code true} if successful, {@code false} if the
   *  mark bit was already set.
   */
  @Inline
  private boolean testAndMark(ObjectReference object, byte value) {
    Word oldValue;
    do {
      oldValue = VM.objectModel.prepareAvailableBits(object);
      byte markBit = (byte) (oldValue.toInt() & (inNurseryGC ? LOS_BIT_MASK : MARK_BIT));
      if (markBit == value) return false;
    } while (!VM.objectModel.attemptAvailableBits(object, oldValue,
                                                  oldValue.and(Word.fromIntZeroExtend(LOS_BIT_MASK).not()).or(Word.fromIntZeroExtend(value))));
    return true;
  }

  /**
   * Return {@code true} if the mark bit for an object has the given value.
   *
   * @param object The object whose mark bit is to be tested
   * @param value The value against which the mark bit will be tested
   * @return {@code true} if the mark bit for the object has the given value.
   */
  @Inline
  private boolean testMarkBit(ObjectReference object, byte value) {
    return (byte) (VM.objectModel.readAvailableByte(object) & MARK_BIT) == value;
  }

  /**
   * Return {@code true} if the object is in the logical nursery
   *
   * @param object The object whose status is to be tested
   * @return {@code true} if the object is in the logical nursery
   */
  @Inline
  private boolean isInNursery(ObjectReference object) {
     return (byte)(VM.objectModel.readAvailableByte(object) & NURSERY_BIT) == NURSERY_BIT;
  }

  @Override
  @Inline
  protected int superPageHeaderSize() {
    return Treadmill.headerSize();
  }

  @Override
  @Inline
  protected int cellHeaderSize() {
    return 0;
  }

  /**
   * This is the treadmill used by the large object space.
   *
   * Note that it depends on the specific local in use whether this
   * is being used.
   *
   * @return The treadmill associated with this large object space.
   */
  public Treadmill getTreadmill() {
    return this.treadmill;
  }
}
