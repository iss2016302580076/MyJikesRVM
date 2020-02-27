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
package org.mmtk.plan.mygc;

import org.mmtk.plan.Plan;
import org.mmtk.plan.StopTheWorld;
import org.mmtk.plan.Trace;
import org.mmtk.plan.generational.Gen;
import org.mmtk.policy.CopySpace;
import org.mmtk.policy.ImmortalSpace;
import org.mmtk.policy.Space;
import org.mmtk.utility.heap.VMRequest;
import org.mmtk.utility.options.Options;
import org.mmtk.utility.sanitychecker.SanityChecker;
import org.mmtk.utility.statistics.*;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;


/**
 * This class implements the global state of a a simple allocator
 * without a collector.
 */
@Uninterruptible
public class MyGC extends StopTheWorld {

  /*****************************************************************************
   * Class variables
   */

  /** Fraction of available virtual memory to give to the nursery (if contiguous) */
  protected static final float NURSERY_VM_FRACTION = 0.15f;  //fraction of vitual space of nusery

  /** Switch between a contiguous and discontiguous nursery (experimental) */
  static final boolean USE_DISCONTIGUOUS_NURSERY = false;

  // Allocators
  public static final int ALLOC_NURSERY        = ALLOC_DEFAULT;
  public static final int ALLOC_OBSERVER         = StopTheWorld.ALLOCATORS + 1;
  public static final int ALLOC_MATURE         = StopTheWorld.ALLOCATORS + 2;
  public static final int ALLOC_MATURE_MINORGC = StopTheWorld.ALLOCATORS + 3;
  public static final int ALLOC_MATURE_MAJORGC = StopTheWorld.ALLOCATORS + 4;

  public static final int SCAN_NURSERY = 0;
  public static final int SCAN_MATURE  = 1;
  public static final int SCAN_OBSERVER  = 2;

  /* Statistics */
  protected static final BooleanCounter fullHeap = new BooleanCounter("majorGC", true, true);
  private static final Timer fullHeapTime = new Timer("majorGCTime", false, true);
  protected static final EventCounter wbFast;
  protected static final EventCounter wbSlow;
  public static final SizeCounter nurseryMark;
  public static final SizeCounter nurseryCons;
  private static final float WORST_CASE_COPY_EXPANSION = 1.5f;
  public static final boolean IGNORE_REMSETS = false;


  /* The nursery space is where all new objects are allocated by default */
  private static final VMRequest vmRequest = USE_DISCONTIGUOUS_NURSERY ? VMRequest.discontiguous() : VMRequest.highFraction(NURSERY_VM_FRACTION);
  public static final CopySpace nurserySpace = new CopySpace("nursery", false, vmRequest);

  public static final int NURSERY = nurserySpace.getDescriptor();
  private static final Address NURSERY_START = nurserySpace.getStart();

  public boolean gcFullHeap = false;
  public boolean nextGCFullHeap = false;




  /**
   *
   */
  public static final ImmortalSpace noGCSpace = new ImmortalSpace("default", VMRequest.discontiguous());
  public static final int NOGC = noGCSpace.getDescriptor();


  /*****************************************************************************
   * Instance variables
   */

  /**
   *
   */
  public final Trace nurseryTrace = new Trace(metaDataSpace);


  /*
   * Class initializer
   */
  static {
    if (GATHER_WRITE_BARRIER_STATS) {
      wbFast = new EventCounter("wbFast");
      wbSlow = new EventCounter("wbSlow");
    } else {
      wbFast = null;
      wbSlow = null;
    }
    if (Stats.GATHER_MARK_CONS_STATS) {
      nurseryMark = new SizeCounter("nurseryMark", true, true);
      nurseryCons = new SizeCounter("nurseryCons", true, true);
    } else {
      nurseryMark = null;
      nurseryCons = null;
    }
  }


  /*****************************************************************************
   * Collection
   */

  /**
   * {@inheritDoc}
   */

  @Override
  public void forceFullHeapCollection() {
    nextGCFullHeap = true;
  }




  @Inline
  @Override
  public final void collectionPhase(short phaseId) {

    if (phaseId == SET_COLLECTION_KIND) {
      super.collectionPhase(phaseId);
      gcFullHeap = requiresFullHeapCollection();
      return;
    }

    if (phaseId == PREPARE) {
      nurserySpace.prepare(true);
      if (gcFullHeap) {
        if (Stats.gatheringStats()) fullHeap.set();
        fullHeapTime.start();
      }
      super.collectionPhase(phaseId);
      return;
    }

    if (phaseId == STACK_ROOTS) {
      VM.scanning.notifyInitialThreadScanComplete(!traceFullHeap());
      setGCStatus(GC_PROPER);
      return;
    }

    if (phaseId == CLOSURE) {
      if (!traceFullHeap()) {
        nurseryTrace.prepare();
      }
      return;
    }

    if (phaseId == RELEASE) {
      nurserySpace.release();
      switchNurseryZeroingApproach(nurserySpace);
      if (!traceFullHeap()) {
        nurseryTrace.release();
      } else {
        super.collectionPhase(phaseId);
        if (gcFullHeap) fullHeapTime.stop();
      }

      nextGCFullHeap = (getPagesAvail() < Options.nurserySize.getMinNursery());
      return;
    }

    super.collectionPhase(phaseId);

  }

  public final boolean traceFullHeap() {
    return IGNORE_REMSETS || gcFullHeap;
  }

  @Override
  public final boolean isCurrentGCNursery() {
    return !(IGNORE_REMSETS || gcFullHeap);
  }

  @Override
  public final boolean lastCollectionFullHeap() {
    return gcFullHeap;
  }

  protected boolean requiresFullHeapCollection() {
    if (userTriggeredCollection && Options.fullHeapSystemGC.getValue()) {
      return true;
    }

    if (nextGCFullHeap || collectionAttempt > 1) {
      // Forces full heap collection
      return true;
    }

    if (virtualMemoryExhausted()) {
      return true;
    }

    return false;
  }



  private boolean virtualMemoryExhausted() {
    return ((int)(getCollectionReserve() * WORST_CASE_COPY_EXPANSION)) >= getMaturePhysicalPagesAvail();
  }

  public int getMaturePhysicalPagesAvail() {
    //TODO:To be Override
    return 0;
  };

}
