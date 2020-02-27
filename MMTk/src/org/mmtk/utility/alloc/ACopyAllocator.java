package org.mmtk.utility.alloc;

import static org.mmtk.utility.Constants.*;
import org.mmtk.policy.Space;
import org.mmtk.utility.Conversions;
import org.mmtk.utility.Log;
import org.mmtk.utility.Constants.*;
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

public class ACopyAllocator extends Allocator {

    /****************************************************************************
     *
     * Class variables
     */

    // Block size defines slow path periodicity.

    /**
     *
     */
    private static final int LOG_DEFAULT_STEP_SIZE = 30; // 1G: let the external slow path dominate
    private static final int STEP_SIZE = 1 << (SUPPORT_CARD_SCANNING ? LOG_CARD_BYTES : LOG_DEFAULT_STEP_SIZE);
    protected static final int LOG_BLOCK_SIZE = LOG_BYTES_IN_PAGE + 3;
    protected static final Word BLOCK_MASK = Word.one().lsh(LOG_BLOCK_SIZE).minus(Word.one());
    private static final int BLOCK_SIZE = (1 << LOG_BLOCK_SIZE);


    // Offsets into header
    protected static final Offset REGION_LIMIT_OFFSET = Offset.zero();
    protected static final Offset NEXT_REGION_OFFSET = REGION_LIMIT_OFFSET.plus(BYTES_IN_ADDRESS);
    protected static final Offset DATA_END_OFFSET = NEXT_REGION_OFFSET.plus(BYTES_IN_ADDRESS);
    protected static final Offset WRITE_DEGREE_OFFSET = DATA_END_OFFSET.plus(BYTES_IN_INT);


    // Data must start particle-aligned.
//  protected static final Offset DATA_START_OFFSET = alignAllocationNoFill(
//      Address.zero().plus(DATA_END_OFFSET.plus(BYTES_IN_ADDRESS)),
//      MIN_ALIGNMENT, 0).toWord().toOffset();
//  protected static final Offset MAX_DATA_START_OFFSET = alignAllocationNoFill(
//      Address.zero().plus(DATA_END_OFFSET.plus(BYTES_IN_ADDRESS)),
//      MAX_ALIGNMENT, 0).toWord().toOffset();

    protected static final Offset DATA_START_OFFSET = alignAllocationNoFill(
            Address.zero().plus(WRITE_DEGREE_OFFSET.plus(BYTES_IN_INT)),
            MIN_ALIGNMENT, 0).toWord().toOffset();
    protected static final Offset MAX_DATA_START_OFFSET = alignAllocationNoFill(
            Address.zero().plus(WRITE_DEGREE_OFFSET.plus(BYTES_IN_INT)),
            MAX_ALIGNMENT, 0).toWord().toOffset();

    public static final int MINIMUM_DATA_SIZE = (1 << LOG_BLOCK_SIZE) - MAX_DATA_START_OFFSET.toInt();

    private static final int SIZE_OF_TWO_X86_CACHE_LINES_IN_BYTES = 128;

    private static final boolean VERBOSE = false;

    /****************************************************************************
     *
     * Instance variables
     */

    /** insertion point */
    protected Address cursor;
    /** current internal slow-path sentinel for bump pointer */
    private Address internalLimit;
    /** current external slow-path sentinel for bump pointer */
    private Address limit;
    /**  space this bump pointer is associated with */
    protected Space space;

    protected int spaceWriteThreshold;

    /** first contiguous region */
    protected Address initialRegion;
    /** linear scanning is permitted if true */
    protected final boolean allowScanning;
    /** current contiguous region */
    protected Address region;


    /**
     * Constructor.
     *
     * @param space The space to bump point into.
     * @param allowScanning Allow linear scanning of this region of memory.
     */
    protected ACopyAllocator(Space space, boolean allowScanning) {
        this.space = space;
        this.spaceWriteThreshold = 3;

        this.allowScanning = allowScanning;
        reset();
    }

    /**
     * Reset the allocator. Note that this does not reset the space.
     * This is must be done by the caller.
     */
    public final void reset() {
        cursor = Address.zero();
        limit = Address.zero();
        internalLimit = Address.zero();
        initialRegion = Address.zero();
        region = Address.zero();
    }

    /**
     * Re-associate this bump pointer with a different space. Also
     * reset the bump pointer so that it will use the new space
     * on the next call to <code>alloc</code>.
     *
     * @param space The space to associate the bump pointer with.
     */
    public final void rebind(Space space) {
        reset();
        this.space = space;
        this.spaceWriteThreshold += 1;
    }

    /**
     * Allocate space for a new object.  This is frequently executed code and
     * the coding is deliberately sensitive to the optimizing compiler.
     * After changing this, always check the IR/MC that is generated.
     *
     * @param bytes The number of bytes allocated
     * @param align The requested alignment
     * @param offset The offset from the alignment
     * @return The address of the first byte of the allocated region
     */
    @Inline
    public final Address alloc(int bytes, int align, int offset) {
        Address start = alignAllocationNoFill(cursor, align, offset);
        Address end = start.plus(bytes);

        if(!region.isZero()){
//        System.out.println(getRegionLimit(region));
//        System.out.println(getNextRegion(region));
//        System.out.println(getDataEnd(region));
//        System.out.println(getDataStart(region));
//            if(!getNextRegion(region).isZero())
//                System.out.println("aaaa");
            int writeDegree = region.plus(NEXT_REGION_OFFSET).loadInt();
        }

        if (end.GT(internalLimit))
            return allocSlow(start, end, align, offset);
        fillAlignmentGap(cursor, start);
        cursor = end;
        end.plus(SIZE_OF_TWO_X86_CACHE_LINES_IN_BYTES).prefetch();
        return start;
    }

    /**
     * Internal allocation slow path.  This is called whenever the bump
     * pointer reaches the internal limit.  The code is forced out of
     * line.  If required we perform an external slow path take, which
     * we inline into this method since this is already out of line.
     *
     * @param start The start address for the pending allocation
     * @param end The end address for the pending allocation
     * @param align The requested alignment
     * @param offset The offset from the alignment
     * @return The address of the first byte of the allocated region
     */
    @NoInline
    private Address allocSlow(Address start, Address end, int align,
                              int offset) {
        Address rtn = null;
        Address card = null;
        if (SUPPORT_CARD_SCANNING)
            card = getCard(start.plus(CARD_MASK)); // round up
        if (end.GT(limit)) { /* external slow path */
            rtn = allocSlowInline(end.diff(start).toInt(), align, offset);
            if (SUPPORT_CARD_SCANNING && card.NE(getCard(rtn.plus(CARD_MASK))))
                card = getCard(rtn); // round down
        } else {             /* internal slow path */
            while (internalLimit.LE(end))
                internalLimit = internalLimit.plus(STEP_SIZE);
            if (internalLimit.GT(limit))
                internalLimit = limit;
            fillAlignmentGap(cursor, start);
            cursor = end;
            rtn = start;
        }
        if (SUPPORT_CARD_SCANNING && !rtn.isZero())
            createCardAnchor(card, rtn, end.diff(start).toInt());
        return rtn;
    }

    /**
     * Given an allocation which starts a new card, create a record of
     * where the start of the object is relative to the start of the
     * card.
     *
     * @param card An address that lies within the card to be marked
     * @param start The address of an object which creates a new card.
     * @param bytes The size of the pending allocation in bytes (used for debugging)
     */
    private void createCardAnchor(Address card, Address start, int bytes) {
        if (VM.VERIFY_ASSERTIONS) {
            VM.assertions._assert(allowScanning);
            VM.assertions._assert(card.EQ(getCard(card)));
            VM.assertions._assert(start.diff(card).sLE(MAX_DATA_START_OFFSET));
            VM.assertions._assert(start.diff(card).toInt() >= -CARD_MASK);
        }
        while (bytes > 0) {
            int offset = start.diff(card).toInt();
            getCardMetaData(card).store(offset);
            card = card.plus(1 << LOG_CARD_BYTES);
            bytes -= (1 << LOG_CARD_BYTES);
        }
    }

    /**
     * Return the start of the card corresponding to a given address.
     *
     * @param address The address for which the card start is required
     * @return The start of the card containing the address
     */
    private static Address getCard(Address address) {
        return address.toWord().and(Word.fromIntSignExtend(CARD_MASK).not()).toAddress();
    }

    /**
     * Return the address of the metadata for a card, given the address of the card.
     * @param card The address of some card
     * @return The address of the metadata associated with that card
     */
    private static Address getCardMetaData(Address card) {
        Address metadata = EmbeddedMetaData.getMetaDataBase(card);
        return metadata.plus(EmbeddedMetaData.getMetaDataOffset(card, LOG_CARD_BYTES - LOG_CARD_META_SIZE, LOG_CARD_META_SIZE));
    }

    /**
     * External allocation slow path (called by superclass when slow path is
     * actually taken.  This is necessary (rather than a direct call
     * from the fast path) because of the possibility of a thread switch
     * and corresponding re-association of bump pointers to kernel
     * threads.
     *
     * @param bytes The number of bytes allocated
     * @param offset The offset from the alignment
     * @param align The requested alignment
     * @return The address of the first byte of the allocated region or
     * zero on failure
     */
    @Override
    protected final Address allocSlowOnce(int bytes, int align, int offset) {
        /* Check we have been bound to a space */
        if (space == null) {
            VM.assertions.fail("Allocation on unbound bump pointer.");
        }

        Extent blockSize = Word.fromIntZeroExtend(bytes).plus(BLOCK_MASK)
                .and(BLOCK_MASK.not()).toExtent();

        /* Check if we already have a block to use */
        if (allowScanning && !region.isZero()) {
            Address nextRegion = getNextRegion(region);
            if (!nextRegion.isZero()) {
//                return consumeNextRegion(nextRegion, bytes, align, offset);
                System.out.println("current region: "+region+" has nextRegion slot: " + nextRegion);
                Address via = nextRegion;
                System.out.print(via);
                while (!getNextRegion(via).isZero()){
                    via = getNextRegion(via);
                    System.out.print("->"+via);
                }
                System.out.println("");
                Address availableNext = consumeNextRegionWithLowWrite(nextRegion, bytes, align, offset, blockSize);

                if(!availableNext.isZero()) {
                    System.out.println("finally find the available region: " + region);
                    via = availableNext;
                    System.out.print(via);
                    while (!getNextRegion(via).isZero()){
                        via = getNextRegion(via);
                        System.out.print("->"+via);
                    }
                    System.out.println("");
                    return availableNext;
                }else {
                    spaceWriteThreshold ++;
                    System.out.println("the nextRegionList donot has available nextRegion");
                }
            }
        }

        /* Acquire space, block aligned, that can accommodate the request */



//        Extent blockSize = maximumRegionSize();
        Address start = acquireRegionWithLowWrite(bytes, align, offset, blockSize);
//        Address start = space.acquire(Conversions.bytesToPages(blockSize));

        if (start.isZero()) return start; // failed allocation

        if (!allowScanning) { // simple allocator
            if (start.NE(limit)) cursor = start;  // discontiguous
            updateLimit(start.plus(blockSize), start, bytes);
        } else                // scannable allocator
            updateMetaData(start, blockSize, bytes);
        return alloc(bytes, align, offset);
    }

    protected final Address acquireRegionWithLowWrite(int bytes, int align, int offset, Extent blockSize){

        Address start = space.acquire(Conversions.bytesToPages(blockSize));

        //use random to simulate write degree
        if (!start.isZero() && (getWriteDegree(start) == 0)){
            final int i = (int) (Math.random()*(spaceWriteThreshold*2+1));
            setWriteDegree(start, i);
            System.out.println("region:"+start+"  with write_degree: "+ i);
        }

        Address unavailableStart;
        if (region.isZero()){
            unavailableStart = Address.zero();
        }
        else {
            unavailableStart = getNextRegion(region);
        }


        while (!start.isZero() && getWriteDegree(start) > this.spaceWriteThreshold){
            setNextRegion(start, unavailableStart);
            unavailableStart = start;
            start = space.acquire(Conversions.bytesToPages(blockSize));

            //use random to simulate write degree
            if (!start.isZero() && (getWriteDegree(start) == 0)){
                final int i = (int) (Math.random()*(spaceWriteThreshold*2+1));
                setWriteDegree(start, i);
                System.out.println("region: "+start+"  with write_degree: "+ i);
            }

        }
        setNextRegion(start, unavailableStart);
        return start;
    }

    /**
     * Update the limit pointer.  As a side effect update the internal limit
     * pointer appropriately.
     *
     * @param newLimit The new value for the limit pointer
     * @param start The start of the region to be allocated into
     * @param bytes The size of the pending allocation (if any).
     */
    @Inline
    protected final void updateLimit(Address newLimit, Address start, int bytes) {
        limit = newLimit;
        internalLimit = start.plus(STEP_SIZE);
        if (internalLimit.GT(limit))
            internalLimit = limit;
        else {
            while (internalLimit.LT(cursor.plus(bytes)))
                internalLimit = internalLimit.plus(STEP_SIZE);
            if (VM.VERIFY_ASSERTIONS)
                VM.assertions._assert(internalLimit.LE(limit));
        }
    }

    /**
     * A bump pointer chunk/region has been consumed but the contiguous region
     * is available, so consume it and then return the address of the start
     * of a memory region satisfying the outstanding allocation request.  This
     * is relevant when re-using memory, as in a mark-compact collector.
     *
     * @param nextRegion The region to be consumed
     * @param bytes The number of bytes allocated
     * @param align The requested alignment
     * @param offset The offset from the alignment
     * @return The address of the first byte of the allocated region or
     * zero on failure
     */
    private Address consumeNextRegion(Address nextRegion, int bytes, int align,
                                      int offset) {
        setNextRegion(region,cursor);
        region = nextRegion;
        cursor = getDataStart(nextRegion);
        updateLimit(getRegionLimit(nextRegion), nextRegion, bytes);
        setDataEnd(nextRegion,Address.zero());
        VM.memory.zero(false, cursor, limit.diff(cursor).toWord().toExtent());
        reusePages(Conversions.bytesToPages(limit.diff(region)));

        return alloc(bytes, align, offset);
    }

    private Address consumeNextRegionWithLowWrite(Address nextRegion, int bytes, int align, int offset, Extent blockSize){
        Address unavailableStart = Address.zero();
        while (!nextRegion.isZero() && getWriteDegree(nextRegion) > spaceWriteThreshold){
            Address oldNext = getNextRegion(nextRegion);
            setNextRegion(nextRegion, unavailableStart);
            unavailableStart = nextRegion;
            nextRegion = oldNext;
        }

        //if none of the nextList is available, reset the next for region
        if (nextRegion.isZero()){
            setNextRegion(region,unavailableStart);
            return nextRegion;//return null
        }

        //else find an available region, rebuild the linktable
        Address via = nextRegion;
        while (!getNextRegion(via).isZero()){
            via = getNextRegion(via);
        }
        setNextRegion(via, unavailableStart);
        setNextRegion(region, nextRegion);
        setDataEnd(region,cursor);

        //then use the nextRegion
        region = nextRegion;
        cursor = getDataStart(region);
        updateLimit(nextRegion.plus(BLOCK_SIZE), nextRegion, bytes);
        setDataEnd(nextRegion,Address.zero());
        setRegionLimit(region,limit);

        return alloc(bytes, align, offset);
    }
    /******************************************************************************
     *
     *   Accessor methods for the region metadata fields.
     *
     */

    /**
     * The first offset in a region after the header
     * @param region The region
     * @return The lowest address at which data can be stored
     */
    @Inline
    public static Address getDataStart(Address region) {
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!region.isZero());
        return region.plus(DATA_START_OFFSET);
    }

    /**
     * The next region in the linked-list of regions
     * @param region The region
     * @return The next region in the list
     */
    @Inline
    public static Address getNextRegion(Address region) {
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!region.isZero());
        Address result = region.plus(NEXT_REGION_OFFSET).loadAddress();
        return result;
    }

    /**
     * Set the next region in the linked-list of regions
     * @param region The region
     * @param nextRegion the next region in the list
     */
    @Inline
    public static void setNextRegion(Address region, Address nextRegion) {
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!region.isZero());
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!nextRegion.EQ(Address.fromIntZeroExtend(0xdeadbeef)));
        region.store(nextRegion,NEXT_REGION_OFFSET);
    }

    /**
     * Clear the next region pointer in the linked-list of regions
     * @param region The region
     */
    @Inline
    public static void clearNextRegion(Address region) {
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!region.isZero());
        region.store(Address.zero(),NEXT_REGION_OFFSET);
    }

    @Inline
    public static int getWriteDegree(Address region){
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!region.isZero());
        return region.plus(WRITE_DEGREE_OFFSET).loadInt();
    }

    @Inline
    public static  void  setWriteDegree(Address region, int newWriteDegree){
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!region.isZero());
        region.store(newWriteDegree, WRITE_DEGREE_OFFSET);
    }

    @Inline
    public static  void  clearWriteDegree(Address region, int newWriteDegree){
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!region.isZero());
        region.store(0, WRITE_DEGREE_OFFSET);
    }

    /**
     * @param region The bump-pointer region
     * @return The DATA_END address from the region header
     */
    @Inline
    public static Address getDataEnd(Address region) {
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!region.isZero());
        return region.plus(DATA_END_OFFSET).loadAddress();
    }

    /**
     * @param region The bump-pointer region
     * @param endAddress The new DATA_END address from the region header
     */
    public static void setDataEnd(Address region, Address endAddress) {
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!region.isZero());
        region.store(endAddress, DATA_END_OFFSET);
        if (VERBOSE) {
            Log.write("setDataEnd(");
            Log.write(region);
            Log.write(",");
            Log.write(endAddress);
            Log.writeln(")");
        }
    }

    /**
     * Return the end address of the given region.
     * @param region The region.
     * @return the allocation limit of the region.
     */
    public static Address getRegionLimit(Address region) {
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!region.isZero());
        return region.plus(REGION_LIMIT_OFFSET).loadAddress();
    }

    /**
     * Stores the limit value at the end of the region.
     *
     * @param region region address
     * @param limit the limit value
     */
    public static void setRegionLimit(Address region, Address limit) {
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!region.isZero());
        region.plus(REGION_LIMIT_OFFSET).store(limit);
    }

    /**
     * @param region The region.
     * @return {@code true} if the address is region-aligned
     */
    public static boolean isRegionAligned(Address region) {
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!region.isZero());
        return region.toWord().and(BLOCK_MASK).isZero();
    }

    /**
     * Sanity check a region header
     * @param region Region to check
     */
    public static void checkRegionMetadata(Address region) {
        if (VM.VERIFY_ASSERTIONS) {
            Address nextRegion = getNextRegion(region);
            Address dataStart = getDataStart(region);
            Address dataEnd = getDataEnd(region);
            Address regionLimit = getRegionLimit(region);

            VM.assertions._assert(nextRegion.isZero() || isRegionAligned(nextRegion));
            VM.assertions._assert(dataEnd.GE(dataStart));
            if (dataEnd.GT(regionLimit)) {
                Log.write("dataEnd=", dataEnd);
                Log.writeln(", regionLimit=", regionLimit);
            }
            VM.assertions._assert(dataEnd.LE(regionLimit));
            VM.assertions._assert(regionLimit.EQ(region.plus(BLOCK_SIZE)));
        }

    }
    /**
     * Update the metadata to reflect the addition of a new region.
     *
     * @param start The start of the new region
     * @param size The size of the new region (rounded up to block-alignment)
     * @param bytes the size of the pending allocation, if any
     */
    @Inline
    private void updateMetaData(Address start, Extent size, int bytes) {

        if (initialRegion.isZero()) {
            /* this is the first allocation */
            initialRegion = start;
            region = start;
            cursor = region.plus(DATA_START_OFFSET);
        } else if (limit.NE(start) ||
                region.diff(start.plus(size)).toWord().toExtent().GT(maximumRegionSize())) {
            /* non contiguous or over-size, initialize new region */
            setNextRegion(region,start);
            setDataEnd(region,cursor);
            region = start;
            cursor = start.plus(DATA_START_OFFSET);
        }
        updateLimit(start.plus(size), start, bytes);
        setRegionLimit(region,limit);
    }

    /**
     * Gather data for GCspy. <p>
     * This method calls the drivers linear scanner to scan through
     * the objects allocated by this bump pointer.
     *
     * @param driver The GCspy driver for this space.
     */
    public void gcspyGatherData(LinearSpaceDriver driver) {
        //driver.setRange(space.getStart(), cursor);
        driver.setRange(space.getStart(), limit);
        this.linearScan(driver.getScanner());
    }

    /**
     * Gather data for GCspy. <p>
     * This method calls the drivers linear scanner to scan through
     * the objects allocated by this bump pointer.
     *
     * @param driver The GCspy driver for this space.
     * @param scanSpace The space to scan
     */
    public void gcspyGatherData(LinearSpaceDriver driver, Space scanSpace) {
        //TODO can scanSpace ever be different to this.space?
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(scanSpace == space, "scanSpace != space");

        //driver.setRange(scanSpace.getStart(), cursor);
        Address start = scanSpace.getStart();
        driver.setRange(start, limit);

        if (false) {
            Log.write("\nBumpPointer.gcspyGatherData set Range ", scanSpace.getStart());
            Log.write(" to ", limit);
            Log.writeln("BumpPointergcspyGatherData scan from ", initialRegion);
        }

        linearScan(driver.getScanner());
    }


    /**
     * Perform a linear scan through the objects allocated by this bump pointer.
     *
     * @param scanner The scan object to delegate scanning to.
     */
    @Inline
    public final void linearScan(LinearScan scanner) {
        if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(allowScanning);
        /* Has this allocator ever allocated anything? */
        if (initialRegion.isZero()) return;

        /* Loop through active regions or until the last region */
        Address start = initialRegion;
        while (!start.isZero()) {
            scanRegion(scanner, start); // Scan this region
            start = getNextRegion(start); // Move on to next
        }
    }

    /**
     * Perform a linear scan through a single contiguous region
     *
     * @param scanner The scan object to delegate to.
     * @param start The start of this region
     */
    @Inline
    private void scanRegion(LinearScan scanner, Address start) {
        /* Get the end of this region */
        Address dataEnd = start.plus(DATA_END_OFFSET).loadAddress();

        /* dataEnd = zero represents the current region. */
        Address currentLimit = (dataEnd.isZero() ? cursor : dataEnd);
        if (currentLimit.EQ(start.plus(DATA_END_OFFSET).plus(BYTES_IN_ADDRESS))) {
            /* Empty region, so we can not call getObjectFromStartAddress() */
            return;
        }

        ObjectReference current = VM.objectModel.getObjectFromStartAddress(start.plus(DATA_START_OFFSET));

        /* Loop through each object up to the limit */
        do {
            /* Read end address first, as scan may be destructive */
            Address currentObjectEnd = VM.objectModel.getObjectEndAddress(current);
            scanner.scan(current);
            if (currentObjectEnd.GE(currentLimit)) {
                /* We have scanned the last object */
                break;
            }
            /* Find the next object from the start address (dealing with alignment gaps, etc.) */
            ObjectReference next = VM.objectModel.getObjectFromStartAddress(currentObjectEnd);
            if (VM.VERIFY_ASSERTIONS) {
                /* Must be monotonically increasing */
                VM.assertions._assert(next.toAddress().GT(current.toAddress()));
            }
            current = next;
        } while (true);
    }

    /**
     * Some pages are about to be re-used to satisfy a slow path request.
     * @param pages The number of pages.
     */
    protected void reusePages(int pages) {
        VM.assertions.fail("Subclasses that reuse regions must override this method.");
    }

    /**
     * Maximum size of a single region. Important for children that implement
     * load balancing or increments based on region size.
     * @return the maximum region size
     */
    protected Extent maximumRegionSize() {
        Extent blockSize = Word.fromIntZeroExtend(0).plus(BLOCK_MASK)
                .and(BLOCK_MASK.not()).toExtent();
        return  blockSize;
//    return Extent.max();
    }

    /** @return the current cursor value */
    public final Address getCursor() {
        return cursor;
    }

    /** @return the space associated with this bump pointer */
    @Override
    public final Space getSpace() {
        return space;
    }

    /**
     * Print out the status of the allocator (for debugging)
     */
    public final void show() {
        Log.write("cursor = ", cursor);
        if (allowScanning) {
            Log.write(" region = ", region);
        }
        Log.writeln(" limit = ", limit);
    }
}
