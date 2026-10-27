package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.LDValue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * One store on its own: what staging, committing and closing a batch leave on the disk and in the count,
 * with and without persistence, and once persistence has been given up on.
 */
public class EventStoreTest {
    private static final int CAPACITY = 100;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Rule
    public Timeout globalTimeout = Timeout.seconds(30);
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private final LDLogger logger = LDLogger.withAdapter(Logs.none(), "");

    private File directory() {
        return new File(tempFolder.getRoot(), "events");
    }

    private EventStore store() {
        return store(CAPACITY, true);
    }

    private EventStore store(int capacity, boolean persistEvents) {
        return new EventStore(directory(), capacity, persistEvents, logger);
    }

    /** A store whose every write fails, so its first commit gives up on the disk. */
    private EventStore unwritableStore(int capacity) {
        return new EventStore(directory(), capacity, true, logger) {
            @Override
            FileOutputStream openForAppending(File file) throws IOException {
                throw new IOException("No space left on device");
            }
        };
    }

    private static byte[] event(String key) {
        return ("{\"kind\":\"custom\",\"key\":\"" + key + "\"}").getBytes(UTF8);
    }

    private static List<String> keysIn(byte[] body) {
        List<String> keys = new ArrayList<>();
        for (LDValue event : LDValue.parse(new String(body, UTF8)).values()) {
            keys.add(event.get("key").stringValue());
        }
        return keys;
    }

    /** The keys of every event in the store's files, read straight off the disk rather than through the store. */
    private List<String> keysOnDisk() throws IOException {
        List<String> keys = new ArrayList<>();
        File[] files = directory().listFiles();
        if (files == null) {
            return keys;
        }
        Arrays.sort(files);
        for (File file : files) {
            for (byte[] payload : EventLogFormat.eventPayloads(Files.readAllBytes(file.toPath()))) {
                keys.add(LDValue.parse(new String(payload, UTF8)).get("key").stringValue());
            }
        }
        return keys;
    }

    private File onlyFileNamed(String prefix) {
        File[] files = directory().listFiles((dir, name) -> name.startsWith(prefix));
        assertNotNull(files);
        assertEquals(1, files.length);
        return files[0];
    }

    private static EventStore.Batch closeOne(EventStore store) {
        List<EventStore.Batch> closed = store.closeBatch();
        assertEquals(1, closed.size());
        return closed.get(0);
    }

    // staging

    @Test
    public void aStagedEventStaysOffTheDiskUntilItIsCommitted() throws IOException {
        EventStore store = store();
        store.stage(event("staged-only"));

        assertEquals(Collections.emptyList(), keysOnDisk());
        assertEquals(1, store.getPendingEventCount());
    }

    @Test
    public void aCommittedEventIsOnTheDiskWhereAnotherProcessWouldFindIt() throws IOException {
        EventStore store = store();
        store.stage(event("committed"));
        store.commit();

        assertEquals(Arrays.asList("committed"), keysOnDisk());
    }

    @Test
    public void theStoreNeverWritesOnItsOwnHoweverMuchIsStaged() throws IOException {
        // When to write is the caller's decision. 64 KB is well past what a store that wrote on its own would
        // let build up.
        char[] padding = new char[1_024];
        Arrays.fill(padding, 'p');
        byte[] event = event(new String(padding));
        EventStore store = store();
        for (int i = 0; i < 64; i++) {
            assertTrue(store.stage(event));
        }

        assertEquals(Collections.emptyList(), keysOnDisk());
        assertEquals(64, store.getPendingEventCount());

        store.commit();

        assertEquals(64, keysOnDisk().size());
    }

    // recovery

    @Test
    public void aDeliveredBatchLeavesTheNextRunNothingToRecover() {
        EventStore store = store();
        store.stage(event("only"));
        store.commit();
        store.remove(closeOne(store));
        store.close();

        EventStore nextRun = store();
        nextRun.preparePayloadsFromInterruptedLogs();

        assertEquals(Collections.emptyList(), nextRun.pendingBatches());
        assertEquals(0, nextRun.getPendingEventCount());
    }

    @Test
    public void everyEventBeforeAFrameTheWriterDidNotFinishIsRecovered() throws IOException {
        EventStore store = store();
        for (int i = 0; i < 3; i++) {
            store.stage(event("event-" + i));
        }
        store.commit();
        store.close();

        // A process killed partway through a write leaves the last frame short of its declared length.
        File log = onlyFileNamed(DiskEventLog.PREFIX);
        byte[] whole = Files.readAllBytes(log.toPath());
        Files.write(log.toPath(), Arrays.copyOf(whole, whole.length - 8));

        EventStore nextRun = store();
        assertEquals("the torn frame's event, counted lost", 1, nextRun.preparePayloadsFromInterruptedLogs());
        List<EventStore.Batch> recovered = nextRun.pendingBatches();
        assertEquals(1, recovered.size());
        assertEquals(2, recovered.get(0).eventCount);
        assertEquals(Arrays.asList("event-0", "event-1"), keysIn(nextRun.body(recovered.get(0))));
    }

    @Test
    public void aLogFromAPreviousRunWhoseFirstFrameIsDamagedIsDeletedAndCountedLost() throws IOException {
        EventStore store = store();
        store.stage(event("damaged"));
        store.commit();
        store.close();
        File log = onlyFileNamed(DiskEventLog.PREFIX);
        byte[] damaged = Files.readAllBytes(log.toPath());
        damaged[EventLogFormat.FILE_HEADER_SIZE + EventLogFormat.FRAME_HEADER_SIZE] ^= 0x01;
        Files.write(log.toPath(), damaged);

        EventStore nextRun = store();
        assertEquals(1, nextRun.preparePayloadsFromInterruptedLogs());
        assertEquals(Collections.emptyList(), nextRun.pendingBatches());
        assertFalse(log.exists());
    }

    @Test
    public void aLogFromAPreviousRunInAFormatThisVersionDoesNotReadIsDeletedAndCountedLost() throws IOException {
        EventStore store = store();
        store.stage(event("unreadable"));
        store.commit();
        store.close();
        File log = onlyFileNamed(DiskEventLog.PREFIX);
        byte[] unreadable = Files.readAllBytes(log.toPath());
        unreadable[4] = (byte) 0xFF;
        unreadable[5] = (byte) 0xFF;
        Files.write(log.toPath(), unreadable);

        EventStore nextRun = store();
        assertEquals(1, nextRun.preparePayloadsFromInterruptedLogs());
        assertEquals(Collections.emptyList(), nextRun.pendingBatches());
        assertFalse(log.exists());
    }

    @Test
    public void aLogFromAPreviousRunHoldingNothingIsDeletedWithoutALoss() throws IOException {
        assertTrue(directory().mkdirs() || directory().isDirectory());
        File headerOnly = new File(directory(), DiskEventLog.PREFIX + UUID.randomUUID());
        Files.write(headerOnly.toPath(), EventLogFormat.fileHeader());
        // A log whose first write never reached the disk.
        File empty = new File(directory(), DiskEventLog.PREFIX + UUID.randomUUID());
        Files.write(empty.toPath(), new byte[0]);

        EventStore nextRun = store();
        assertEquals(0, nextRun.preparePayloadsFromInterruptedLogs());
        assertEquals(Collections.emptyList(), nextRun.pendingBatches());
        assertFalse(headerOnly.exists());
        assertFalse(empty.exists());
    }

    @Test
    public void aBatchNamedWithoutACountInAFormatThisVersionDoesNotReadIsDiscarded() throws IOException {
        EventStore store = store();
        store.stage(event("unreadable"));
        store.commit();
        closeOne(store);
        File counted = onlyFileNamed("ready-");
        File uncounted = new File(directory(), counted.getName().substring(0, counted.getName().lastIndexOf('.')));
        assertTrue(counted.renameTo(uncounted));
        byte[] corrupted = Files.readAllBytes(uncounted.toPath());
        corrupted[4] = (byte) 0xFF;
        corrupted[5] = (byte) 0xFF;
        Files.write(uncounted.toPath(), corrupted);

        EventStore reader = store();
        assertEquals(Collections.emptyList(), reader.pendingBatches());
        // A batch that can never be delivered is not left to occupy the device either.
        assertFalse(uncounted.exists());
    }

    // closing batches

    @Test
    public void thereIsNothingToCloseWhenNothingWasRecorded() {
        assertEquals(Collections.emptyList(), store().closeBatch());
        assertEquals(Collections.emptyList(), store(CAPACITY, false).closeBatch());
        assertEquals(Collections.emptyList(), unwritableStore(CAPACITY).closeBatch());
    }

    @Test
    public void eachBatchHasAPayloadIdOfItsOwn() {
        // The ID is what lets the service discard a retry, so two batches sharing one would lose the second.
        for (boolean persist : new boolean[] {true, false}) {
            EventStore store = new EventStore(new File(tempFolder.getRoot(), "persist-" + persist), CAPACITY,
                    persist, logger);
            store.stage(event("first"));
            EventStore.Batch first = closeOne(store);
            store.stage(event("second"));
            EventStore.Batch second = closeOne(store);

            assertNotEquals("persisting: " + persist, first.payloadId, second.payloadId);
        }
    }

    @Test
    public void aStoreWithoutPersistenceListsItsBatchesOldestFirstAsTheStoreWithADiskDoes() throws IOException {
        EventStore store = store(CAPACITY, false);
        List<EventStore.Batch> closed = new ArrayList<>();
        // Enough that hash order coming out right by chance is not a worry.
        for (int i = 0; i < 20; i++) {
            store.stage(event("event-" + i));
            closed.add(closeOne(store));
        }

        assertEquals(closed, store.pendingBatches());

        store.remove(closed.remove(3));
        assertEquals(closed, store.pendingBatches());
        assertEquals(Arrays.asList("event-4"), keysIn(store.body(closed.get(3))));
    }

    @Test
    public void aBatchHeldInMemoryHasTheBodyABatchOnDiskWouldHave() throws IOException {
        EventStore written = store();
        written.stage(event("same"));
        written.commit();
        byte[] fromDisk = written.body(closeOne(written));

        EventStore held = store(CAPACITY, false);
        held.stage(event("same"));
        assertArrayEquals(fromDisk, held.body(closeOne(held)));

        EventStore givenUp = unwritableStore(CAPACITY);
        givenUp.stage(event("same"));
        givenUp.commit();
        assertFalse(givenUp.isPersisting());
        assertArrayEquals(fromDisk, givenUp.body(closeOne(givenUp)));
    }

    @Test
    public void eventsHeldInMemoryAreAssembledIntoOneBody() throws IOException {
        EventStore store = store(CAPACITY, false);
        store.stage(event("first"));
        store.stage(event("second"));

        assertEquals("[{\"kind\":\"custom\",\"key\":\"first\"},{\"kind\":\"custom\",\"key\":\"second\"}]",
                new String(store.body(closeOne(store)), UTF8));
    }

    // capacity

    @Test
    public void aClosedBatchStillCountsAgainstCapacityUntilItIsDelivered() {
        for (boolean persist : new boolean[] {true, false}) {
            EventStore store = new EventStore(new File(tempFolder.getRoot(), "persist-" + persist), 1, persist,
                    logger);
            assertTrue(store.stage(event("first")));
            EventStore.Batch batch = closeOne(store);

            assertFalse("persisting: " + persist, store.stage(event("second")));

            store.remove(batch);
            assertTrue("persisting: " + persist, store.stage(event("second")));
        }
    }

    @Test
    public void whatAnEarlierRunLeftUndeliveredCountsFromTheFirstListingOn() {
        // The bound the capacity documents: the processor lists the disk in the background after starting, so
        // what an earlier run left only counts from that listing on.
        EventStore earlier = store(2, true);
        earlier.stage(event("earlier-1"));
        earlier.stage(event("earlier-2"));
        closeOne(earlier);
        earlier.close();

        EventStore store = store(2, true);
        assertTrue(store.stage(event("before-listing")));

        store.preparePayloadsFromInterruptedLogs();
        assertEquals(1, store.pendingBatches().size());
        assertEquals(3, store.getPendingEventCount());
        assertFalse(store.stage(event("after-listing")));
    }

    // persistence given up on

    @Test
    public void aStoreThatGaveUpOnTheDiskDeliversItsEventsFromMemoryInTheOrderTheyWereRecorded()
            throws IOException {
        EventStore store = unwritableStore(CAPACITY);
        store.stage(event("first"));
        store.commit();
        assertFalse(store.isPersisting());
        // Recording carries on, and what the failed commit could not write is still the store's to deliver.
        assertTrue(store.stage(event("second")));
        assertEquals(2, store.getPendingEventCount());

        EventStore.Batch batch = closeOne(store);
        assertEquals(2, batch.eventCount);
        assertEquals(Arrays.asList("first", "second"), keysIn(store.body(batch)));
        assertEquals(Arrays.asList(batch), store.pendingBatches());

        store.remove(batch);
        assertEquals(Collections.emptyList(), store.pendingBatches());
        assertEquals(0, store.getPendingEventCount());
    }
}
