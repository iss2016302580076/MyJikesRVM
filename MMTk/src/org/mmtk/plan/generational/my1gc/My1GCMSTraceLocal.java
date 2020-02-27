package org.mmtk.plan.generational.my1gc;

import org.mmtk.plan.Trace;
import org.mmtk.plan.TraceLocal;
import org.mmtk.plan.generational.Gen;
import org.mmtk.plan.generational.GenCollector;
import org.mmtk.policy.Space;
import org.mmtk.harness.vm.ObjectModel;
import org.mmtk.vm.VM;
import org.vmmagic.unboxed.ObjectReference;

public class My1GCMSTraceLocal extends TraceLocal {


    public My1GCMSTraceLocal(Trace trace, GenCollector plan) {
        super(My1GC.SCAN_MS, trace);
    }

    private static My1GC global() {
        return (My1GC) VM.activePlan.global();
    }

    @Override
    public ObjectReference traceObject(ObjectReference object) {
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(global().traceFullHeap());
        if (object.isNull()) return object;

        if (Space.isInSpace(My1GC.MS0, object)||Space.isInSpace(My1GC.MS1, object)){
            if(ObjectModel.getId(object)>0){
                return My1GC.markSweepSpace.traceObject(this,object);
            }
        }


        return super.traceObject(object);
    }


    @Override
    public boolean isLive(ObjectReference object) {
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!object.isNull());
        if (Space.isInSpace(My1GC.nurserySpace.getDescriptor(),object)) {
            return Gen.nurserySpace.isLive(object);
        }
        if (object.isNull()) return false;
        if (Space.isInSpace(My1GC.MS0, object))
            return My1GC.hi ? My1GC.matureSpace0.isLive(object) : true;
        if (Space.isInSpace(My1GC.MS1, object))
            return My1GC.hi ? true : My1GC.matureSpace1.isLive(object);
        if(Space.isInSpace(My1GC.MSS,object))
            return true;
        return super.isLive(object);
    }

    @Override
    public boolean willNotMoveInCurrentCollection(ObjectReference object) {
        if (Space.isInSpace(My1GC.toSpaceDesc(), object)) {
            return false;
        }
        if (Space.isInSpace(My1GC.fromSpaceDesc(), object)) {
            return false;
        }
        if(Space.isInSpace(My1GC.MSS,object)){
            return true;
        }
        return super.willNotMoveInCurrentCollection(object);
    }

}
