package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.android.integrations.EventProcessorBuilder;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What bounds the store holds an application's events within: the byte capacity, with the share of it
 * held back for summaries, and the maximum age past which stored events are discarded.
 */
public class EventStoreBoundsTest {
    private static final String MOBILE_KEY = "mob-key";
    private static final String OTHER_MOBILE_KEY = "mob-other-key";
    private static final int CAPACITY = 100;
    private static final long BYTE_CAPACITY = 1000;
    private static final int FRAME = 100;

    @Rule
    public Timeout globalTimeout = Timeout.seconds(30);
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private final LDLogger logger = LDLogger.withAdapter(Logs.none(), "");

    private EventStore boundedStore(boolean persistEvents) {
        return new EventStore(new File(tempFolder.getRoot(), "events"), "test", CAPACITY, BYTE_CAPACITY,
                EventProcessorBuilder.DEFAULT_MAX_AGE_MILLIS, persistEvents, logger);
    }

    private EventStore environmentStore(String mobileKey, String processName) {
        return EventStore.create(tempFolder.getRoot(), mobileKey, processName, CAPACITY,
                EventProcessorBuilder.DEFAULT_CAPACITY_BYTES, EventProcessorBuilder.DEFAULT_MAX_AGE_MILLIS,
                true, logger);
    }

    /** An event whose frame in the log, header included, is exactly {@code frameBytes} long. */
    private static byte[] event(int frameBytes, String key) {
        String bare = "{\"kind\":\"custom\",\"key\":\"" + key + "\",\"data\":\"\"}";
        int padding = frameBytes - EventLogFormat.FRAME_HEADER_SIZE - bare.length();
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < padding; i++) {
            data.append('x');
        }
        return ("{\"kind\":\"custom\",\"key\":\"" + key + "\",\"data\":\"" + data + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void stageFrames(EventStore store, int count) {
        for (int i = 1; i <= count; i++) {
            assertTrue(store.stage(event(FRAME, "event-" + i)));
        }
    }

    private static File onlyBatchFile(EventStore store) {
        File[] files = store.getDirectory().listFiles((dir, name) -> name.startsWith("ready-"));
        assertNotNull(files);
        assertEquals(1, files.length);
        return files[0];
    }

    private static void age(File file, int days) {
        assertTrue(file.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days)));
    }

    private static void ageContents(File directory, int days) {
        File[] files = directory.listFiles();
        assertNotNull(files);
        for (File file : files) {
            age(file, days);
        }
    }

    // byte capacity

    @Test
    public void refusesFullEventsPastWhatItHoldsBackForSummaries() {
        EventStore store = boundedStore(true);
        stageFrames(store, 8);

        assertFalse(store.hasRoomForEventBytes());
        assertFalse(store.stage(event(FRAME, "event-9")));
        assertEquals(8, store.getPendingEventCount());
    }

    @Test
    public void takesSummariesIntoWhatItHoldsBackAndRefusesThemPastTheByteCapacity() {
        EventStore store = boundedStore(true);
        stageFrames(store, 8);

        assertTrue(store.stage(event(FRAME, "summary-1"), true));
        assertTrue(store.stage(event(FRAME, "summary-2"), true));
        assertFalse(store.stage(event(FRAME, "summary-3"), true));
        assertEquals(10, store.getPendingEventCount());
    }

    @Test
    public void refusesAReservedEventThatTurnsOutNotToFitAndGivesUpItsReservation() {
        EventStore store = boundedStore(true);
        stageFrames(store, 7);
        store.reserve(1);

        assertFalse(store.stageReserved(event(2 * FRAME, "too-big")));
        assertEquals(7, store.getPendingEventCount());
        assertTrue(store.stage(event(FRAME, "fits")));
    }

    @Test
    public void makesRoomAgainOnceABatchIsDelivered() {
        EventStore store = boundedStore(true);
        stageFrames(store, 8);
        List<EventStore.Batch> closed = store.closeBatch();
        assertEquals(1, closed.size());
        assertFalse(store.stage(event(FRAME, "while-held")));

        store.startedSending(closed.get(0));
        assertTrue(store.stage(event(FRAME, "while-sending")));
        store.remove(closed.get(0));
        assertTrue(store.hasRoomForEventBytes());
    }

    @Test
    public void countsCommittedBytesAsWellAsStagedOnes() {
        EventStore store = boundedStore(true);
        stageFrames(store, 8);
        store.commit();

        assertFalse(store.stage(event(FRAME, "after-commit")));
    }

    @Test
    public void countsTheBytesOfABatchAnotherProcessClosedFromTheListingWithoutReadingIt() {
        File directory = new File(tempFolder.getRoot(), "events");
        EventStore writer = new EventStore(directory, "writer", CAPACITY, true, logger);
        stageFrames(writer, 8);
        assertEquals(1, writer.closeBatch().size());

        AtomicInteger reads = new AtomicInteger();
        EventStore other = new EventStore(directory, "other", CAPACITY, BYTE_CAPACITY,
                EventProcessorBuilder.DEFAULT_MAX_AGE_MILLIS, true, logger) {
            @Override
            InputStream openForReading(File file) throws IOException {
                reads.incrementAndGet();
                return new FileInputStream(file);
            }
        };
        assertEquals(1, other.pendingBatches().size());

        assertEquals(0, reads.get());
        assertFalse(other.hasRoomForEventBytes());
        assertFalse(other.stage(event(FRAME, "other")));
    }

    @Test
    public void boundsTheBatchesItHoldsInMemoryOnceItHasGivenUpOnTheDisk() {
        EventStore store = new EventStore(new File(tempFolder.getRoot(), "events"), "test", CAPACITY,
                BYTE_CAPACITY, EventProcessorBuilder.DEFAULT_MAX_AGE_MILLIS, true, logger) {
            @Override
            FileOutputStream openForAppending(File log) throws IOException {
                throw new IOException("no space left on device");
            }
        };
        stageFrames(store, 8);
        store.commit();
        assertFalse(store.isPersisting());
        assertEquals(1, store.closeBatch().size());

        assertFalse(store.stage(event(FRAME, "past-capacity")));
    }

    @Test
    public void isNotBoundedByBytesWhereItKeepsEventsOffTheDisk() {
        EventStore store = boundedStore(false);
        stageFrames(store, 20);

        assertTrue(store.hasRoomForEventBytes());
        assertEquals(20, store.getPendingEventCount());
    }

    // expiry

    @Test
    public void discardsABatchLastWrittenLongerAgoThanTheMaximumAgeAndStopsCountingIt() {
        EventStore store = environmentStore(MOBILE_KEY, "test");
        stageFrames(store, 2);
        store.closeBatch();
        age(onlyBatchFile(store), 8);

        assertEquals(2, store.discardExpired());

        assertTrue(store.pendingBatches().isEmpty());
        assertEquals(0, store.getPendingEventCount());
    }

    @Test
    public void keepsABatchYoungerThanTheMaximumAge() {
        EventStore store = environmentStore(MOBILE_KEY, "test");
        stageFrames(store, 1);
        store.closeBatch();
        age(onlyBatchFile(store), 6);

        assertEquals(0, store.discardExpired());
        assertEquals(1, store.pendingBatches().size());
    }

    @Test
    public void leavesABatchWhoseRequestIsOutHoweverOldItIs() {
        EventStore store = environmentStore(MOBILE_KEY, "test");
        stageFrames(store, 1);
        EventStore.Batch batch = store.closeBatch().get(0);
        store.startedSending(batch);
        age(onlyBatchFile(store), 8);

        assertEquals(0, store.discardExpired());
        assertTrue(onlyBatchFile(store).exists());
    }

    @Test
    public void discardsTheStaleLogOfAProcessThatIsGoneButNeverOneALiveProcessHolds() throws IOException {
        EventStore store = environmentStore(MOBILE_KEY, "test");
        stageFrames(store, 1);
        store.commit();
        EventStore gone = environmentStore(MOBILE_KEY, "gone");
        stageFrames(gone, 2);
        gone.commit();
        gone.close();
        File opening = new File(store.getDirectory(), DiskEventLog.OPENING_PREFIX + "left-by-a-crash");
        assertTrue(opening.createNewFile());
        ageContents(store.getDirectory(), 8);

        assertEquals(2, store.discardExpired());

        List<String> remaining = new ArrayList<>();
        for (File file : store.getDirectory().listFiles()) {
            remaining.add(file.getName());
        }
        assertEquals(1, remaining.size());
        assertTrue(remaining.get(0).startsWith(DiskEventLog.PREFIX));
        assertTrue("the live store's log was discarded", store.pendingEventPayloads().size() == 1);
    }

    @Test
    public void removesTheDirectoryOfAnEnvironmentNothingHasWrittenToForTheMaximumAge() {
        EventStore abandoned = environmentStore(OTHER_MOBILE_KEY, "test");
        stageFrames(abandoned, 3);
        abandoned.closeBatch();
        abandoned.close();
        File abandonedDirectory = abandoned.getDirectory();
        ageContents(abandonedDirectory, 8);

        EventStore store = environmentStore(MOBILE_KEY, "test");

        assertEquals(3, store.discardExpired());
        assertFalse(abandonedDirectory.exists());
    }

    @Test
    public void keepsTheDirectoryOfAnEnvironmentWrittenToRecently() {
        EventStore inUse = environmentStore(OTHER_MOBILE_KEY, "test");
        stageFrames(inUse, 1);
        inUse.closeBatch();
        File inUseDirectory = inUse.getDirectory();

        EventStore store = environmentStore(MOBILE_KEY, "test");

        assertEquals(0, store.discardExpired());
        assertEquals(1, inUseDirectory.listFiles().length);
    }

    @Test
    public void looksAtOtherEnvironmentsOnlyOncePerStore() {
        EventStore store = environmentStore(MOBILE_KEY, "test");
        assertEquals(0, store.discardExpired());

        EventStore abandoned = environmentStore(OTHER_MOBILE_KEY, "test");
        stageFrames(abandoned, 1);
        abandoned.closeBatch();
        abandoned.close();
        ageContents(abandoned.getDirectory(), 8);

        assertEquals(0, store.discardExpired());
        assertTrue(abandoned.getDirectory().exists());
    }

    @Test
    public void leavesTheSiblingsOfADirectoryTheSdkDidNotChoose() {
        File sibling = new File(tempFolder.getRoot(), "unrelated");
        EventStore neighbor = new EventStore(sibling, "test", CAPACITY, true, logger);
        stageFrames(neighbor, 1);
        neighbor.closeBatch();
        neighbor.close();
        ageContents(sibling, 8);

        EventStore store = new EventStore(new File(tempFolder.getRoot(), "events"), "test", CAPACITY, true,
                logger);
        stageFrames(store, 1);
        store.commit();

        assertEquals(0, store.discardExpired());
        assertEquals(1, sibling.listFiles().length);
    }

    @Test
    public void discardsNothingWhereItKeepsEventsOffTheDisk() {
        EventStore abandoned = environmentStore(OTHER_MOBILE_KEY, "test");
        stageFrames(abandoned, 1);
        abandoned.closeBatch();
        abandoned.close();
        ageContents(abandoned.getDirectory(), 8);

        EventStore store = EventStore.create(tempFolder.getRoot(), MOBILE_KEY, "test", CAPACITY,
                EventProcessorBuilder.DEFAULT_CAPACITY_BYTES, EventProcessorBuilder.DEFAULT_MAX_AGE_MILLIS,
                false, logger);

        assertEquals(0, store.discardExpired());
        assertEquals(1, abandoned.getDirectory().listFiles().length);
    }
}
