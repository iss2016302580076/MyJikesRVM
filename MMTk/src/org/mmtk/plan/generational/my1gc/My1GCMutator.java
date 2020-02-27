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

import org.mmtk.plan.generational.GenMutator;
import org.mmtk.policy.CopyLocal;
import org.mmtk.policy.MarkSweepLocal;
import org.mmtk.policy.Space;
import org.mmtk.utility.alloc.Allocator;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;

/**
 * This class implements <i>per-mutator thread</i> behavior and state for
 * the <code>My1GC</code> two-generational copying collector.<p>
 *
 * Specifically, this class defines mutator-time semantics specific to the
 * mature generation (<code>GenMutator</code> defines nursery semantics).
 * In particular the mature space allocator is defined (for mutator-time
 * allocation into the mature space via pre-tenuring), and the mature space
 * per-mutator thread collection time semantics are defined (rebinding
 * the mature space allocator).<p>
 *
 * @see My1GC for a description of the <code>My1GC</code> algorithm.
 *
 * @see My1GC
 * @see My1GCCollector
 * @see GenMutator
 * @see org.mmtk.plan.StopTheWorldMutator
 * @see org.mmtk.plan.MutatorContext
 */
@Uninterruptible
public class My1GCMutator extends GenMutator {
  /******************************************************************
   * Instance fields
   */

  /**
   * The allocator for the copying mature space (the mutator may
   * "pretenure" objects into this space otherwise used only by
   * the collector)
   */
  private final CopyLocal mature;
  private final MarkSweepLocal markSweep;

  /****************************************************************************
   *
   * Initialization
   */

  /**
   * Constructor
   */
  public My1GCMutator() {
    mature = new CopyLocal();
    markSweep = new MarkSweepLocal(((My1GC) VM.activePlan.global()).markSweepSpace);
  }

  /**
   * Called before the MutatorContext is used, but after the context has been
   * fully registered and is visible to collection.
   */
  @Override
  public void initMutator(int id) {
    super.initMutator(id);
    mature.rebind(My1GC.toSpace());
  }

  /****************************************************************************
   *
   * Mutator-time allocation
   */

  /**
   * {@inheritDoc}
   */
  @Override
  @Inline
  public final Address alloc(int bytes, int align, int offset, int allocator, int site) {
    if (allocator == My1GC.ALLOC_MATURE) {
      return mature.alloc(bytes, align, offset);
    }
    else if(allocator == My1GC.ALLOC_MARKSWEEP){
      return markSweep.alloc(bytes, align, offset);
    }
    return super.alloc(bytes, align, offset, allocator, site);
  }

  @Override
  @Inline
  public final void postAlloc(ObjectReference object, ObjectReference typeRef,
      int bytes, int allocator) {
    // nothing to be done
    if (allocator == My1GC.ALLOC_MATURE) return;
    super.postAlloc(object, typeRef, bytes, allocator);
  }

  @Override
  public final Allocator getAllocatorFromSpace(Space space) {
    if (space == My1GC.matureSpace0 || space == My1GC.matureSpace1) return mature;
    if (space == My1GC.markSweepSpace) return markSweep;
    return super.getAllocatorFromSpace(space);
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
      if (phaseId == My1GC.RELEASE) {
        super.collectionPhase(phaseId, primary);
        if (global().gcFullHeap) mature.rebind(My1GC.toSpace());
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

}
