package org.mmtk.plan.generational.immix_writer;


import static org.mmtk.policy.immix.ImmixConstants.MARK_LINE_AT_SCAN_TIME;

import org.mmtk.plan.generational.GenCollector;
import org.mmtk.plan.generational.GenMatureTraceLocal;
import org.mmtk.plan.Trace;
import org.mmtk.policy.Space;

import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

/**
 * This class implements the core functionality for a transitive
 * closure over the heap graph, specifically in a generational immix
 * collector.
 */
@Uninterruptible
public final class GenIWMatureTraceLocal extends GenMatureTraceLocal{

    /**
     * @param global the global nurseryTrace class to use
     * @param plan the state of the generational collector
     */
    public GenIWMatureTraceLocal(Trace global, GenCollector plan) {
        super(GenIW.SCAN_IMMIX, global, plan);
    }

    @Override
    @Inline
    public ObjectReference traceObject(ObjectReference object) {
        if (object.isNull()) return object;

        if (Space.isInSpace(GenIW.IMMIX, object)) {
            return GenIW.immixSpace.fastTraceObject(this, object);
        }
        return super.traceObject(object);
    }

    @Override
    public boolean isLive(ObjectReference object) {
        if (object.isNull()) return false;
        if (Space.isInSpace(GenIW.IMMIX, object)) {
            return GenIW.immixSpace.isLive(object);
        }
        return super.isLive(object);
    }

    @Override
    public boolean willNotMoveInCurrentCollection(ObjectReference object) {
        if (Space.isInSpace(GenIW.IMMIX, object)) {
            return true;
        }
        return super.willNotMoveInCurrentCollection(object);
    }

    @Inline
    @Override
    protected void scanObject(ObjectReference object) {
        super.scanObject(object);
        if (MARK_LINE_AT_SCAN_TIME && Space.isInSpace(GenIW.IMMIX, object))
            GenIW.immixSpace.markLines(object);
    }
}
