package org.mmtk.plan.generational.immix_writer;


import org.mmtk.plan.Plan;
import org.mmtk.plan.TraceLocal;
import org.mmtk.plan.generational.*;
import org.mmtk.policy.Space;
import org.mmtk.utility.HeaderByte;
import org.mmtk.utility.alloc.Allocator;
import org.mmtk.utility.alloc.ImmixAllocator;
import org.mmtk.utility.statistics.Stats;

import org.mmtk.vm.VM;

import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.*;

/**
 * This class implements <i>per-collector thread</i> behavior and state for
 * the <code>GenImmix</code> two-generational copying collector.<p>
 *
 * Specifically, this class defines semantics specific to the collection of
 * the copy generation (<code>GenCollector</code> defines nursery semantics).
 * In particular the copy space allocator is defined (for collection-time
 * allocation into the copy space), and the copy space per-collector thread
 * collection time semantics are defined.<p>
 *
 * @see GenIW for a description of the <code>GenImmix</code> algorithm.
 *
 * @see GenIW
 * @see GenIWMutator
 * @see GenCollector
 * @see org.mmtk.plan.StopTheWorldCollector
 * @see org.mmtk.plan.CollectorContext
 */
@Uninterruptible
public class GenIWCollector extends GenCollector {

    /*****************************************************************************
     *
     * Instance fields
     */

    /**
     *
     */
    private final GenIWMatureTraceLocal matureTrace = new GenIWMatureTraceLocal(global().matureTrace, this);
    private final GenIWMatureDefragTraceLocal defragTrace = new GenIWMatureDefragTraceLocal(global().matureTrace, this);

    private final org.mmtk.policy.immix.CollectorLocal immix = new org.mmtk.policy.immix.CollectorLocal(GenIW.immixSpace);

    private final ImmixAllocator copy = new ImmixAllocator(GenIW.immixSpace, true, false);
    private final ImmixAllocator defragCopy = new ImmixAllocator(GenIW.immixSpace, true, true);

    /****************************************************************************
     *
     * Collection-time allocation
     */

    /**
     * {@inheritDoc}
     */
    @Override
    @Inline
    public final Address allocCopy(ObjectReference original, int bytes,
                                   int align, int offset, int allocator) {

        if (Stats.GATHER_MARK_CONS_STATS) {
            if (Space.isInSpace(GenIW.NURSERY, original)) GenIW.nurseryMark.inc(bytes);
        }
        if (allocator == Plan.ALLOC_LOS) {
            if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(Allocator.getMaximumAlignedSize(bytes, align) > Plan.MAX_NON_LOS_COPY_BYTES);
            return los.alloc(bytes, align, offset);
        } else {
            if (VM.VERIFY_ASSERTIONS) {
                VM.assertions._assert(bytes <= Plan.MAX_NON_LOS_COPY_BYTES);
                if (GenIW.immixSpace.inImmixCollection())
                    VM.assertions._assert(allocator == GenIW.ALLOC_MATURE_MAJORGC);
                else
                    VM.assertions._assert(allocator == GenIW.ALLOC_MATURE_MINORGC);
            }
            if (GenIW.immixSpace.inImmixDefragCollection()) {
                return defragCopy.alloc(bytes, align, offset);
            } else
                return copy.alloc(bytes, align, offset);
        }
    }

    @Override
    @Inline
    public final void postCopy(ObjectReference object, ObjectReference typeRef,
                               int bytes, int allocator) {
        if (allocator == Plan.ALLOC_LOS)
            Plan.loSpace.initializeHeader(object, false);
        else {
            if (VM.VERIFY_ASSERTIONS) {
                VM.assertions._assert((!GenIW.immixSpace.inImmixCollection() && allocator == GenIW.ALLOC_MATURE_MINORGC) ||
                        (GenIW.immixSpace.inImmixCollection() && allocator == GenIW.ALLOC_MATURE_MAJORGC));
            }
            GenIW.immixSpace.postCopy(object, bytes, allocator == GenIW.ALLOC_MATURE_MAJORGC);
        }
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
    @Inline
    public void collectionPhase(short phaseId, boolean primary) {
        TraceLocal trace = GenIW.immixSpace.inImmixDefragCollection() ? defragTrace : matureTrace;

        if (global().traceFullHeap()) {
            if (phaseId == GenIW.PREPARE) {
                super.collectionPhase(phaseId, primary);
                trace.prepare();
                copy.reset();
                if (global().gcFullHeap) {
                    immix.prepare(true);
                    defragCopy.reset();
                }
                return;
            }

            if (phaseId == GenIW.CLOSURE) {
                trace.completeTrace();
                return;
            }

            if (phaseId == GenIW.RELEASE) {
                trace.release();
                if (global().gcFullHeap) {
                    immix.release(true);
                    copy.reset();
                }
                super.collectionPhase(phaseId, primary);
                return;
            }
        }

        super.collectionPhase(phaseId, primary);
    }

    @Override
    @Inline
    public final TraceLocal getFullHeapTrace() {
        return GenIW.immixSpace.inImmixDefragCollection() ? defragTrace : matureTrace;
    }

    /****************************************************************************
     *
     * Miscellaneous
     */

    /** @return The active global plan as a <code>GenImmix</code> instance. */
    @Inline
    private static GenIW global() {
        return (GenIW) VM.activePlan.global();
    }
}
