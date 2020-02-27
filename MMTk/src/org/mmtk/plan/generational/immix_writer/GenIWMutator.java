package org.mmtk.plan.generational.immix_writer;



import org.mmtk.plan.generational.*;
import org.mmtk.policy.Space;
import org.mmtk.policy.immix.MutatorLocal;
import org.mmtk.utility.alloc.Allocator;

import org.mmtk.vm.VM;

import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.*;

/**
 * This class implements <i>per-mutator thread</i> behavior and state for
 * the <code>GenImmix</code> two-generational copying collector.<p>
 *
 * Specifically, this class defines mutator-time semantics specific to the
 * mature generation (<code>GenMutator</code> defines nursery semantics).
 * In particular the mature space allocator is defined (for mutator-time
 * allocation into the mature space via pre-tenuring), and the mature space
 * per-mutator thread collection time semantics are defined (rebinding
 * the mature space allocator).<p>
 *
 * See {@link GenIW} for a description of the <code>GenImmix</code> algorithm.
 *
 * @see GenIW
 * @see GenIW
 * @see org.mmtk.plan.generational.GenMutator
 * @see org.mmtk.plan.StopTheWorldMutator
 * @see org.mmtk.plan.MutatorContext
 * @see org.mmtk.plan.Phase
 */
@Uninterruptible
public class GenIWMutator extends GenMutator {

    /******************************************************************
     * Instance fields
     */

    /**
     * The allocator for the mark-sweep mature space (the mutator may
     * "pretenure" objects into this space which is otherwise used
     * only by the collector)
     */
    private final MutatorLocal mature;


    /****************************************************************************
     *
     * Initialization
     */

    /**
     * Constructor
     */
    public GenIWMutator() {
        mature = new MutatorLocal(GenIW.immixSpace, false);
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
        if (allocator == GenIW.ALLOC_MATURE) {
            if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(false); // no pretenuring yet
            return mature.alloc(bytes, align, offset);
        }
        return super.alloc(bytes, align, offset, allocator, site);
    }

    @Override
    @Inline
    public final void postAlloc(ObjectReference ref, ObjectReference typeRef,
                                int bytes, int allocator) {
        if (allocator == GenIW.ALLOC_MATURE) {
            if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(false); // no pretenuring yet
        } else {
            super.postAlloc(ref, typeRef, bytes, allocator);
        }
    }

    @Override
    public Allocator getAllocatorFromSpace(Space space) {
        if (space == GenIW.immixSpace) return mature;
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
    @NoInline
    public void collectionPhase(short phaseId, boolean primary) {
        if (global().traceFullHeap()) {
            if (phaseId == GenIW.PREPARE) {
                super.collectionPhase(phaseId, primary);
                if (global().gcFullHeap) mature.prepare();
                return;
            }

            if (phaseId == GenIW.RELEASE) {
                if (global().gcFullHeap) mature.release();
                super.collectionPhase(phaseId, primary);
                return;
            }
        }

        super.collectionPhase(phaseId, primary);
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
