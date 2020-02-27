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

import org.mmtk.plan.generational.GenConstraints;
import org.vmmagic.pragma.*;

/**
 * My1GC constants.
 */
@Uninterruptible public class My1GCConstraints extends GenConstraints {
    @Override
    public int numSpecializedScans() {
        return 3;
    }
}
