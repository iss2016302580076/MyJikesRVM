package org.mmtk.plan.generational.immix_writer;


        import static org.mmtk.policy.immix.ImmixConstants.MARK_LINE_AT_SCAN_TIME;

        import org.mmtk.plan.generational.GenCollector;
        import org.mmtk.plan.generational.GenMatureTraceLocal;
        import org.mmtk.plan.Trace;
        import org.mmtk.policy.Space;
        import org.mmtk.utility.Log;
        import org.mmtk.utility.options.Options;
        import org.mmtk.vm.VM;

        import org.vmmagic.unboxed.*;
        import org.vmmagic.pragma.*;

/**
 * This class implements the core functionality for a transitive
 * closure over the heap graph, specifically in a defragmenting pass over
 * a generational immix collector.
 */
@Uninterruptible
public final class GenIWMatureDefragTraceLocal extends GenMatureTraceLocal{

    /**
     * @param global the global nurseryTrace class to use
     * @param plan the state of the generational collector
     */
    public GenIWMatureDefragTraceLocal(Trace global, GenCollector plan) {
        super(-1, global, plan);
    }

    @Override
    public boolean isLive(ObjectReference object) {
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(GenIW.immixSpace.inImmixDefragCollection());
        if (object.isNull()) return false;
        if (Space.isInSpace(GenIW.IMMIX, object)) {
            return GenIW.immixSpace.isLive(object);
        }
        return super.isLive(object);
    }

    @Override
    @Inline
    public ObjectReference traceObject(ObjectReference object) {
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(GenIW.immixSpace.inImmixDefragCollection());
        if (object.isNull()) return object;
        if (Space.isInSpace(GenIW.IMMIX, object))
            return GenIW.immixSpace.traceObject(this, object, GenIW.ALLOC_MATURE_MAJORGC);
        return super.traceObject(object);
    }

    @Override
    public boolean willNotMoveInCurrentCollection(ObjectReference object) {
        if (Space.isInSpace(GenIW.IMMIX, object)) {
            return GenIW.immixSpace.willNotMoveThisGC(object);
        }
        return super.willNotMoveInCurrentCollection(object);
    }

    @Inline
    @Override
    protected void scanObject(ObjectReference object) {
        if (VM.VERIFY_ASSERTIONS && Options.verbose.getValue() >= 9) {
            Log.write("SO[");
            Log.write(object);
            Log.writeln("]");
        }
        super.scanObject(object);
        if (MARK_LINE_AT_SCAN_TIME && Space.isInSpace(GenIW.IMMIX, object))
            GenIW.immixSpace.markLines(object);
    }
}
