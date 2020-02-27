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
package org.mmtk.plan.generational.my1gc;

import org.mmtk.plan.Plan;
import org.mmtk.plan.TraceLocal;
import org.mmtk.plan.generational.Gen;
import org.mmtk.plan.generational.GenCollector;
import org.mmtk.policy.CopyLocal;
import org.mmtk.policy.MarkSweepLocal;
import org.mmtk.utility.ForwardingWord;
import org.mmtk.utility.HeaderByte;
import org.mmtk.utility.alloc.Allocator;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;

/**
 * This class implements <i>per-collector thread</i> behavior and state for
 * the <code>My1GC</code> two-generational copying collector.<p>
 *
 * Specifically, this class defines semantics specific to the collection of
 * the mature generation (<code>GenCollector</code> defines nursery semantics).
 * In particular the mature space allocator is defined (for collection-time
 * allocation into the mature space), and the mature space per-collector thread
 * collection time semantics are defined.<p>
 *
 * @see My1GC for a description of the <code>My1GC</code> algorithm.
 *
 * @see My1GC
 * @see My1GCMutator
 * @see GenCollector
 * @see org.mmtk.plan.StopTheWorldCollector
 * @see org.mmtk.plan.CollectorContext
 */
@Uninterruptible
public class My1GCCollector extends GenCollector {

  /******************************************************************
   * Instance fields
   */

  /** The allocator for the mature space */
  private final CopyLocal mature;
  private final MarkSweepLocal markSweep;

  /** The nurseryTrace object for full-heap collections */
  private final My1GCMatureTraceLocal matureTrace;
  private final My1GCMSTraceLocal msTrace;

  /****************************************************************************
   *
   * Initialization
   */

  /**
   * Constructor
   */
  public My1GCCollector() {
    mature = new CopyLocal(My1GC.toSpace());
    markSweep = new MarkSweepLocal(My1GC.markSweepSpace);
    matureTrace = new My1GCMatureTraceLocal(global().matureTrace, this);
    msTrace = new My1GCMSTraceLocal(global().msTrace, this);
  }

  /****************************************************************************
   *
   * Collection-time allocation
   */

  /**
   * {@inheritDoc}
   */
  @Override
  @Inline
  public Address allocCopy(ObjectReference original, int bytes,
      int align, int offset, int allocator) {
    if (allocator == Plan.ALLOC_LOS) {
      if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(Allocator.getMaximumAlignedSize(bytes, align) > Plan.MAX_NON_LOS_COPY_BYTES);
      return los.alloc(bytes, align, offset);
    } else {
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(bytes <= Plan.MAX_NON_LOS_COPY_BYTES);
        if(allocator == My1GC.ALLOC_MARKSWEEP){
          return markSweep.alloc(bytes,align,offset);
        }
        VM.assertions._assert(allocator == My1GC.ALLOC_MATURE_MINORGC ||
            allocator == My1GC.ALLOC_MATURE_MAJORGC);
      }
      return mature.alloc(bytes, align, offset);
    }
  }

  /**
   * {@inheritDoc}<p>
   *
   * In this case we clear any bits used for this object's GC metadata.
   */
  @Override
  @Inline
  public final void postCopy(ObjectReference object, ObjectReference typeRef,
      int bytes, int allocator) {
    ForwardingWord.clearForwardingBits(object);
    if (allocator == Plan.ALLOC_LOS)
      Plan.loSpace.initializeHeader(object, false);
    else if (My1GC.IGNORE_REMSETS)
      My1GC.immortalSpace.traceObject(getCurrentTrace(), object); // FIXME this does not look right
    if (Gen.USE_OBJECT_BARRIER)
      HeaderByte.markAsUnlogged(object);
  }


  /*****************************************************************************
   *
   * Collection
   */

  /**
   * {@inheritDoc}
   */
  @Override
  public void collectionPhase(short phaseId, boolean primary) {
    if (global().traceFullHeap()) {
      if (phaseId == My1GC.PREPARE) {
        super.collectionPhase(phaseId, primary);
        if (global().gcFullHeap) mature.rebind(My1GC.toSpace());
      }
      if (phaseId == My1GC.CLOSURE) {
        matureTrace.completeTrace();
        msTrace.completeTrace();
        return;
      }
      if (phaseId == My1GC.RELEASE) {
        matureTrace.release();
        msTrace.release();
        super.collectionPhase(phaseId, primary);
        return;
      }
    }
    super.collectionPhase(phaseId, primary);
  }

  /*****************************************************************************
   *
   * Miscellaneous
   */

  /** @return The active global plan as a <code>My1GC</code> instance. */
  private static My1GC global() {
    return (My1GC) VM.activePlan.global();
  }

  /** Show the status of the mature allocator. */
  protected final void showMature() {
    mature.show();
  }

  @Override
  public final TraceLocal getFullHeapTrace() {
    return matureTrace;
  }

  public final TraceLocal getMarkSweepTrace(){
    return msTrace;
  }
}
