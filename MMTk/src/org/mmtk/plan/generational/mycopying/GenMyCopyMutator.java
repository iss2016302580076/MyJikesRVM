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
package org.mmtk.plan.generational.mycopying;

import org.mmtk.harness.vm.ObjectModel;
import org.mmtk.plan.generational.Gen;
import org.mmtk.plan.generational.GenMutator;
import org.mmtk.policy.CopyLocal;
import org.mmtk.policy.MyCopyLocal;
import org.mmtk.policy.Space;
import org.mmtk.utility.alloc.Allocator;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Extent;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Word;

/**
 * This class implements <i>per-mutator thread</i> behavior and state for
 * the <code>GenMyCopy</code> two-generational copying collector.<p>
 *
 * Specifically, this class defines mutator-time semantics specific to the
 * mature generation (<code>GenMutator</code> defines nursery semantics).
 * In particular the mature space allocator is defined (for mutator-time
 * allocation into the mature space via pre-tenuring), and the mature space
 * per-mutator thread collection time semantics are defined (rebinding
 * the mature space allocator).<p>
 *
 * @see GenMyCopy for a description of the <code>GenMyCopy</code> algorithm.
 *
 * @see GenMyCopy
 * @see GenMyCopyCollector
 * @see GenMutator
 * @see org.mmtk.plan.StopTheWorldMutator
 * @see org.mmtk.plan.MutatorContext
 */
@Uninterruptible
public class GenMyCopyMutator extends GenMutator {
  /******************************************************************
   * Instance fields
   */

  /**
   * The allocator for the copying mature space (the mutator may
   * "pretenure" objects into this space otherwise used only by
   * the collector)
   */
  private final MyCopyLocal mature;

  /****************************************************************************
   *
   * Initialization
   */

  /**
   * Constructor
   */
  public GenMyCopyMutator() {
    mature = new MyCopyLocal();
  }

  /**
   * Called before the MutatorContext is used, but after the context has been
   * fully registered and is visible to collection.
   */
  @Override
  public void initMutator(int id) {
    super.initMutator(id);
    mature.rebind(GenMyCopy.toSpace());
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
    if (allocator == GenMyCopy.ALLOC_MATURE) {
      return mature.alloc(bytes, align, offset);
    }
    return super.alloc(bytes, align, offset, allocator, site);
  }

  @Override
  @Inline
  public final void postAlloc(ObjectReference object, ObjectReference typeRef,
      int bytes, int allocator) {
    // nothing to be done
    if (allocator == GenMyCopy.ALLOC_MATURE) return;
    super.postAlloc(object, typeRef, bytes, allocator);
  }

  @Override
  public final Allocator getAllocatorFromSpace(Space space) {
    if (space == GenMyCopy.matureSpace0 || space == GenMyCopy.matureSpace1) return mature;
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
      if (phaseId == GenMyCopy.RELEASE) {
        super.collectionPhase(phaseId, primary);
        if (global().gcFullHeap) mature.rebind(GenMyCopy.toSpace());
        return;
      }
    }

    super.collectionPhase(phaseId, primary);
  }

  /*****************************************************************************
   *
   * Miscellaneous
   */

  /** @return The active global plan as a <code>GenMyCopy</code> instance. */
  private static GenMyCopy global() {
    return (GenMyCopy) VM.activePlan.global();
  }


  //not only objectReferenceWrite, but also any other write.
  @Override
  public final void objectReferenceWrite(ObjectReference src, Address slot,
                                               ObjectReference tgt, Word metaDataA,
                                               Word metaDataB, int mode) {
    monitorWriteInMature(tgt);
    super.objectReferenceWrite(src, slot, tgt, metaDataA, metaDataB, mode);
  }

  private void monitorWriteInMature(ObjectReference tgt){
    //if(Space.isInSpace(GenMyCopy.MS0, tgt)||Space.isInSpace(GenMyCopy.MS1, tgt)){
    //cannot use this to judge weather the tgt is in mature

    if(!Gen.inNursery(tgt)){
      //if tgt in the mature, record it's wirte in a word
      //todo: do sth to record this write
    }

  }
}
