package org.mmtk.policy;


import org.mmtk.policy.CopySpace;
import org.mmtk.utility.alloc.ACopyAllocator;
import org.mmtk.utility.alloc.BumpPointer;

import org.vmmagic.pragma.*;
import static org.mmtk.utility.Constants.BYTES_IN_ADDRESS;
import static org.mmtk.utility.Constants.CARD_MASK;
import static org.mmtk.utility.Constants.LOG_BYTES_IN_PAGE;
import static org.mmtk.utility.Constants.LOG_CARD_BYTES;
import static org.mmtk.utility.Constants.LOG_CARD_META_SIZE;
import static org.mmtk.utility.Constants.MAX_ALIGNMENT;
import static org.mmtk.utility.Constants.MIN_ALIGNMENT;
import static org.mmtk.utility.Constants.SUPPORT_CARD_SCANNING;

import org.mmtk.policy.Space;
import org.mmtk.utility.Conversions;
import org.mmtk.utility.Log;
import org.mmtk.utility.gcspy.drivers.LinearSpaceDriver;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Extent;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

/**
 * This class implements unsynchronized (local) elements of a
 * copying collector. Allocation is via the bump pointer
 * (@see BumpPointer).
 *
 * @see BumpPointer
 * @see CopySpace
 */
@Uninterruptible
public final class MyCopyLocal extends ACopyAllocator {


    /**
     * Constructor
     *
     * @param space The space to bump point into.
     */
    public MyCopyLocal(CopySpace space) {
        super(space, true);
    }

    /**
     * Constructor
     */
    public MyCopyLocal() {
        super(null, true);
    }
}

