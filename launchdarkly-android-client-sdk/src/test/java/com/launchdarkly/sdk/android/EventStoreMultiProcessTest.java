package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.LDValue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The event store's behavior when an application runs the SDK in more than one process.
 * <p>
 * An Android application may declare components in another process, and each process gets its own copy of
 * the SDK sharing nothing but the filesystem. From the store's point of view a second process is simply a
 * second instance over the same directory under a different process name, which is what these tests set
 * up. That is not the same thing as a second OS process - it cannot reproduce a genuinely concurrent
 * {@code O_APPEND} write from separate address spaces, which needs an instrumented test - but it does
 * exercise every decision the design makes about sharing: which files a process may touch, which it must
 * leave alone, and what happens when both reach for the same batch.
 */
public class EventStoreMultiProcessTest {
    private static final String MAIN_PROCESS = "com.example.app";
    private static final String SERVICE_PROCESS = "com.example.app:service";
    private static final int CAPACITY = 100;

    @Rule
    public Timeout globalTimeout = Timeout.seconds(30);
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private final LDLogger logger = LDLogger.withAdapter(Logs.none(), "");
    /** Commits run inline, so a test never waits on another thread to make an assertion true. */
    private final Executor inline = Runnable::run;

    private File sharedDirectory() {
        return new File(tempFolder.getRoot(), "events");
    }

    private EventStore storeFor(String processName) {
        return storeFor(processName, CAPACITY);
    }

    private EventStore storeFor(String processName, int capacity) {
        return new EventStore(sharedDirectory(), processName, capacity, true, logger, inline);
    }

    private byte[] event(String key) {
        return ("{\"kind\":\"custom\",\"key\":\"" + key + "\"}").getBytes(Charset.forName("UTF-8"));
    }

    /** The event keys a store can currently see, which is its own log plus every shared batch. */
    private List<String> visibleKeys(EventStore store) {
        List<String> keys = new ArrayList<>();
        for (byte[] payload : store.pendingEventPayloads()) {
            keys.add(LDValue.parse(new String(payload, Charset.forName("UTF-8"))).get("key").stringValue());
        }
        return keys;
    }

    private void overwrite(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(bytes);
        }
    }

    private List<File> filesNamed(String prefix) {
        List<File> matching = new ArrayList<>();
        File[] files = sharedDirectory().listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().startsWith(prefix)) {
                    matching.add(file);
                }
            }
        }
        return matching;
    }

    // MARK: each process writes only its own log

    @Test
    public void eachProcessAppendsToItsOwnLog() {
        EventStore main = storeFor(MAIN_PROCESS);
        EventStore service = storeFor(SERVICE_PROCESS);

        main.stage(event("main-1"));
        main.commit();
        service.stage(event("service-1"));
        service.commit();
        main.stage(event("main-2"));
        main.commit();

        // Two logs, not one. This is the property that makes a spliced frame impossible: only one
        // process ever has the file open for writing.
        assertEquals(2, filesNamed("open-").size());
        assertEquals(Arrays.asList("main-1", "main-2"), visibleKeys(main));
        assertEquals(Arrays.asList("service-1"), visibleKeys(service));
    }

    @Test
    public void processNamesThatLookAlikeInAFilenameStillGetSeparateLogs() {
        // Both are legal Android process names, and a naive sanitization maps both to "com_app_remote".
        EventStore colon = storeFor("com.app:remote");
        EventStore dot = storeFor("com.app.remote");

        colon.stage(event("colon"));
        colon.commit();
        dot.stage(event("dot"));
        dot.commit();

        assertNotEquals(DiskEventLog.nameFor("com.app:remote"), DiskEventLog.nameFor("com.app.remote"));
        assertEquals(2, filesNamed("open-").size());
        assertEquals(Arrays.asList("colon"), visibleKeys(colon));
        assertEquals(Arrays.asList("dot"), visibleKeys(dot));
    }

    @Test
    public void aLogNameIsStableAcrossRunsOfTheSameProcess() {
        // Stability is what lets a process find the log its previous run left behind, so it must not
        // include anything that changes from run to run.
        assertEquals(DiskEventLog.nameFor(MAIN_PROCESS), DiskEventLog.nameFor(MAIN_PROCESS));
    }

    @Test
    public void aProcessNameThePlatformCouldNotDetermineStillYieldsAName() {
        assertTrue(DiskEventLog.nameFor("").length() > 0);
        assertTrue(DiskEventLog.nameFor(null).length() > 0);
        assertNotEquals(DiskEventLog.nameFor(null), DiskEventLog.nameFor(MAIN_PROCESS));
    }

    // MARK: recovery respects the boundary between processes

    @Test
    public void recoveryLeavesAnotherProcessOpenLogAlone() {
        EventStore service = storeFor(SERVICE_PROCESS);
        service.stage(event("service-1"));
        service.commit();

        // The main process starts while the service process is still alive and still appending. Its
        // recovery must not take the log out from under it.
        EventStore main = storeFor(MAIN_PROCESS);
        main.recoverInterruptedLog();

        assertEquals("the other process's log was taken away", 1,
                filesNamed("open-" + DiskEventLog.nameFor(SERVICE_PROCESS)).size());
        assertTrue("another process's events were claimed as recovered", main.pendingBatches().isEmpty());

        // And the service process can still append to it and read back both events.
        service.stage(event("service-2"));
        service.commit();
        assertEquals(Arrays.asList("service-1", "service-2"), visibleKeys(service));
    }

    @Test
    public void aProcessRecoversTheLogItsOwnPreviousRunLeftBehind() {
        EventStore firstRun = storeFor(SERVICE_PROCESS);
        firstRun.stage(event("before-the-crash"));
        firstRun.commit();
        // No close() - the process died without getting the chance.

        EventStore secondRun = storeFor(SERVICE_PROCESS);
        secondRun.recoverInterruptedLog();

        List<EventStore.Batch> recovered = secondRun.pendingBatches();
        assertEquals(1, recovered.size());
        assertEquals(1, recovered.get(0).eventCount);
        assertEquals(Arrays.asList("before-the-crash"), visibleKeys(secondRun));
        assertTrue("the recovered log should no longer be open",
                filesNamed("open-" + DiskEventLog.nameFor(SERVICE_PROCESS)).isEmpty());
    }

    @Test
    public void aCommitBeforeStartupRecoveryDoesNotPassThisRunsEventsOffAsRecovered() {
        // With IMMEDIATE the first commit happens on the caller's thread, and can beat the recovery the
        // processor queues on its own thread at startup. The log has the same name in both runs.
        EventStore firstRun = storeFor(SERVICE_PROCESS);
        firstRun.stage(event("previous-run"));
        firstRun.commit();

        EventStore secondRun = storeFor(SERVICE_PROCESS);
        secondRun.stage(event("this-run"));
        secondRun.commit();
        secondRun.recoverInterruptedLog();

        List<EventStore.Batch> recovered = secondRun.pendingBatches();
        assertEquals(1, recovered.size());
        assertEquals("this run's event was counted as a previous run's", 1, recovered.get(0).eventCount);
        assertEquals(1, filesNamed("open-" + DiskEventLog.nameFor(SERVICE_PROCESS)).size());
        assertEquals(Arrays.asList("previous-run", "this-run"), visibleKeys(secondRun));
    }

    @Test
    public void aStoreWithoutPersistenceLeavesWhatAPersistentRunLeftBehindAlone() {
        EventStore persistentRun = storeFor(SERVICE_PROCESS);
        persistentRun.stage(event("closed-batch"));
        assertNotNull(persistentRun.closeBatch());
        persistentRun.stage(event("open-log"));
        persistentRun.commit();

        EventStore withoutPersistence =
                new EventStore(sharedDirectory(), SERVICE_PROCESS, CAPACITY, false, logger, inline);
        withoutPersistence.recoverInterruptedLog();
        assertTrue("a store without persistence picked up a previous run's events",
                withoutPersistence.pendingBatches().isEmpty());
        assertTrue(visibleKeys(withoutPersistence).isEmpty());

        withoutPersistence.stage(event("in-memory"));
        EventStore.Batch own = withoutPersistence.closeBatch();
        assertNotNull(own);
        assertEquals(Arrays.asList(own), withoutPersistence.pendingBatches());

        assertEquals(1, filesNamed("ready-").size());
        assertEquals(1, filesNamed("open-" + DiskEventLog.nameFor(SERVICE_PROCESS)).size());

        // Still there for the next run that has persistence turned on.
        EventStore persistentAgain = storeFor(SERVICE_PROCESS);
        persistentAgain.recoverInterruptedLog();
        assertEquals(2, persistentAgain.pendingBatches().size());
    }

    @Test
    public void aStoreWithoutPersistenceAsksTheFilesystemNothingEvenAboutABatchItDoesNotHold()
            throws IOException {
        AtomicInteger reads = new AtomicInteger();
        EventStore store = new EventStore(sharedDirectory(), MAIN_PROCESS, CAPACITY, false, logger,
                inline) {
            @Override
            InputStream openForReading(File file) throws IOException {
                reads.incrementAndGet();
                return super.openForReading(file);
            }
        };

        store.stage(event("held"));
        EventStore.Batch held = store.closeBatch();
        assertNotNull(held);
        assertEquals(Arrays.asList(held), store.pendingBatches());
        assertEquals(LDValue.of("held"),
                LDValue.parse(new String(store.body(held), Charset.forName("UTF-8"))).get(0).get("key"));
        store.remove(held);

        // A batch this store never closed, which is what sends the paths that form a file name down the
        // branch that would otherwise ask the filesystem about it.
        EventStore.Batch stranger = new EventStore.Batch(UUID.randomUUID().toString(), 1);
        assertNull(store.body(stranger));
        store.remove(stranger);

        assertEquals(0, reads.get());
        assertFalse("a store without persistence created its directory", sharedDirectory().exists());
    }

    @Test
    public void oneProcessRecoveringDoesNotDisturbWhatAnotherHasAlreadyClosed() {
        EventStore service = storeFor(SERVICE_PROCESS);
        service.stage(event("service-1"));
        EventStore.Batch fromService = service.closeBatch();
        assertNotNull(fromService);

        // Recovery runs when a store is constructed, before that process has recorded anything of its own,
        // which is the only time it is correct to go looking for a log to close.
        EventStore main = storeFor(MAIN_PROCESS);
        main.recoverInterruptedLog();
        main.stage(event("main-1"));
        main.commit();

        // The service process's batch is untouched and still deliverable, by either of them.
        assertEquals(1, filesNamed("ready-").size());
        assertTrue(main.pendingBatches().contains(fromService));
    }

    // MARK: closed batches are shared on purpose

    @Test
    public void aBatchClosedByOneProcessIsDeliverableByAnother() throws IOException {
        // The point of sharing: a service process that records events and is then killed may never run
        // again, so a batch only it could see would never be delivered.
        EventStore service = storeFor(SERVICE_PROCESS);
        service.stage(event("recorded-by-the-service"));
        EventStore.Batch batch = service.closeBatch();
        assertNotNull(batch);

        EventStore main = storeFor(MAIN_PROCESS);
        List<EventStore.Batch> pending = main.pendingBatches();

        assertEquals(1, pending.size());
        EventStore.Batch seenByMain = pending.get(0);
        // The same payload ID, which is what keeps two processes delivering this batch from counting the
        // events twice upstream.
        assertEquals(batch.payloadId, seenByMain.payloadId);
        assertEquals(batch.eventCount, seenByMain.eventCount);

        byte[] body = main.body(seenByMain);
        assertNotNull("the other process could not read the batch", body);
        LDValue parsed = LDValue.parse(new String(body, Charset.forName("UTF-8")));
        assertEquals(1, parsed.size());
        assertEquals(LDValue.of("recorded-by-the-service"), parsed.get(0).get("key"));
    }

    @Test
    public void aBatchThisProcessClosedIsCountedWithoutReadingItBack() throws IOException {
        EventStore main = storeFor(MAIN_PROCESS);
        main.stage(event("first"));
        main.stage(event("second"));
        EventStore.Batch batch = main.closeBatch();
        assertNotNull(batch);

        // Nothing appends to a batch once it is closed, so the count taken at the close still holds and
        // listing it has no reason to go to the disk. Damaging the file is how the test tells the two
        // apart: a listing that read would refuse this batch, which is what the other process does.
        List<File> ready = filesNamed("ready-");
        assertEquals(1, ready.size());
        overwrite(ready.get(0), "not an event log".getBytes(Charset.forName("UTF-8")));

        List<EventStore.Batch> pending = main.pendingBatches();
        assertEquals(1, pending.size());
        assertEquals(batch.payloadId, pending.get(0).payloadId);
        assertEquals(2, pending.get(0).eventCount);

        // A process that did not close it has no count to go on, so it reads, and finds the damage.
        assertTrue(storeFor(SERVICE_PROCESS).pendingBatches().isEmpty());
    }

    @Test
    public void aBatchTheOtherProcessAlreadyDeliveredReadsAsNothingToSend() throws IOException {
        EventStore service = storeFor(SERVICE_PROCESS);
        service.stage(event("delivered-once"));
        EventStore.Batch batch = service.closeBatch();
        assertNotNull(batch);

        EventStore main = storeFor(MAIN_PROCESS);
        main.remove(batch); // main delivered it first

        // The other process asking for the body gets nothing, which its caller treats as already
        // delivered rather than as a failure to retry.
        assertNull(service.body(batch));
    }

    @Test
    public void bothProcessesRemovingTheSameBatchIsHarmless() {
        EventStore service = storeFor(SERVICE_PROCESS);
        service.stage(event("delivered-twice"));
        EventStore.Batch batch = service.closeBatch();
        assertNotNull(batch);

        EventStore main = storeFor(MAIN_PROCESS);
        main.remove(batch);
        service.remove(batch); // a remove of a file that is already gone

        assertTrue(filesNamed("ready-").isEmpty());
        assertEquals(0, main.pendingBatches().size());
        assertEquals(0, service.pendingBatches().size());
    }

    @Test
    public void aProcessCountsAnotherProcessPendingBatchesAgainstItsOwnCapacity() {
        // Deliberately conservative, and documented as such: the alternative is cross-process accounting,
        // which would need cross-process locking to be correct.
        EventStore service = storeFor(SERVICE_PROCESS);
        for (int i = 0; i < 3; i++) {
            service.stage(event("service-" + i));
        }
        assertNotNull(service.closeBatch());

        EventStore main = storeFor(MAIN_PROCESS);
        main.pendingBatches(); // this is what reconciles the count with the directory

        assertEquals(3, main.getPendingEventCount());
    }

    // MARK: a read that fails is no reason to delete

    /**
     * A store whose reads fail while {@code failing} is set, the way they do in a process that has run
     * out of file descriptors: the files are intact and readable again once some are freed.
     */
    private EventStore storeWithFailingReads(String processName, AtomicBoolean failing) {
        return new EventStore(sharedDirectory(), processName, CAPACITY, true, logger, inline) {
            @Override
            InputStream openForReading(File file) throws IOException {
                if (failing.get()) {
                    throw new FileNotFoundException(file + " (Too many open files)");
                }
                return super.openForReading(file);
            }
        };
    }

    @Test
    public void aBatchThatCannotBeReadIsLeftForALaterListing() {
        EventStore service = storeFor(SERVICE_PROCESS);
        service.stage(event("recorded-by-the-service"));
        assertNotNull(service.closeBatch());

        AtomicBoolean failing = new AtomicBoolean(true);
        EventStore main = storeWithFailingReads(MAIN_PROCESS, failing);
        assertTrue(main.pendingBatches().isEmpty());
        assertEquals("a batch that could not be read was deleted", 1, filesNamed("ready-").size());

        failing.set(false);
        List<EventStore.Batch> pending = main.pendingBatches();
        assertEquals(1, pending.size());
        assertEquals(1, pending.get(0).eventCount);
    }

    @Test
    public void aBodyThatCannotBeReadIsAFailureToRetryRatherThanNothingToSend() throws IOException {
        AtomicBoolean failing = new AtomicBoolean(false);
        EventStore main = storeWithFailingReads(MAIN_PROCESS, failing);
        main.stage(event("kept"));
        EventStore.Batch batch = main.closeBatch();
        assertNotNull(batch);

        failing.set(true);
        try {
            main.body(batch);
            fail("a batch that could not be read was reported as having nothing to send");
        } catch (IOException expected) {
            // what the processor retries on
        }
        assertEquals(1, filesNamed("ready-").size());

        failing.set(false);
        byte[] body = main.body(batch);
        assertNotNull(body);
        assertEquals(LDValue.of("kept"),
                LDValue.parse(new String(body, Charset.forName("UTF-8"))).get(0).get("key"));
    }

    @Test
    public void aPreviousRunsLogThatCannotBeReadIsKeptForALaterListing() {
        EventStore firstRun = storeFor(SERVICE_PROCESS);
        firstRun.stage(event("before-the-crash"));
        firstRun.commit();

        AtomicBoolean failing = new AtomicBoolean(true);
        EventStore secondRun = storeWithFailingReads(SERVICE_PROCESS, failing);
        secondRun.recoverInterruptedLog();
        // Moved out from under the name this run appends to, and not deleted.
        assertTrue(filesNamed("open-" + DiskEventLog.nameFor(SERVICE_PROCESS)).isEmpty());
        assertEquals(1, filesNamed("ready-").size());

        secondRun.stage(event("this-run"));
        secondRun.commit();
        failing.set(false);
        List<EventStore.Batch> pending = secondRun.pendingBatches();
        assertEquals(1, pending.size());
        assertEquals(1, pending.get(0).eventCount);
        assertEquals(Arrays.asList("before-the-crash", "this-run"), visibleKeys(secondRun));
    }

    // MARK: a write that fails partway

    /**
     * A store whose writes land whole until {@code whole} of them have, after which each gets all but
     * the last few bytes onto the disk and fails, as one does when the device fills mid-write.
     */
    private EventStore storeFailingPartwayAfter(int whole) {
        AtomicInteger writes = new AtomicInteger();
        return new EventStore(sharedDirectory(), MAIN_PROCESS, CAPACITY, true, logger, inline) {
            @Override
            FileOutputStream openForAppending(File log) throws IOException {
                return new FileOutputStream(log, true) {
                    @Override
                    public void write(byte[] bytes) throws IOException {
                        if (writes.incrementAndGet() <= whole) {
                            super.write(bytes);
                            return;
                        }
                        super.write(bytes, 0, bytes.length - 3);
                        throw new IOException("No space left on device");
                    }
                };
            }
        };
    }

    private List<String> keysIn(byte[] body) {
        List<String> keys = new ArrayList<>();
        for (LDValue event : LDValue.parse(new String(body, Charset.forName("UTF-8"))).values()) {
            keys.add(event.get("key").stringValue());
        }
        return keys;
    }

    @Test
    public void framesAWriteLandedBeforeItFailedAreSentOnceNotFromTheLogAndMemoryBoth()
            throws IOException {
        // The file header and the first event land. The next write gets a whole frame and part of
        // another onto the disk before the device is full.
        EventStore store = storeFailingPartwayAfter(2);

        store.stage(event("written"));
        store.commit();
        store.stage(event("landed"));
        store.stage(event("torn"));
        store.commit();
        assertFalse(store.isPersisting());

        store.closeBatch();

        List<List<String>> bodies = new ArrayList<>();
        for (EventStore.Batch batch : store.pendingBatches()) {
            bodies.add(keysIn(store.body(batch)));
        }
        assertEquals(Arrays.asList(Arrays.asList("written"), Arrays.asList("landed", "torn")), bodies);
    }

    @Test
    public void theNextRunHasNothingToRecoverFromAFirstWriteThatFailedPartway() throws IOException {
        // The file header lands, and then a whole frame and part of another before the device is full.
        EventStore store = storeFailingPartwayAfter(1);

        store.stage(event("landed"));
        store.stage(event("torn"));
        store.commit();
        EventStore.Batch fromMemory = store.closeBatch();
        assertNotNull(fromMemory);
        assertEquals(Arrays.asList("landed", "torn"), keysIn(store.body(fromMemory)));

        // Delivered from memory this run, so the next must not find any of it again in the log.
        EventStore nextRun = storeFor(MAIN_PROCESS);
        nextRun.recoverInterruptedLog();
        assertEquals(0, nextRun.pendingBatches().size());
    }

    // MARK: the two together

    @Test
    public void concurrentRecordingFromTwoProcessesLosesAndCorruptsNothing() throws Exception {
        int eventsPerProcess = 200;
        // Capacity well clear of what the test records, so that a dropped event means a real loss rather
        // than the store correctly refusing to exceed its limit.
        EventStore main = storeFor(MAIN_PROCESS, 10 * eventsPerProcess);
        EventStore service = storeFor(SERVICE_PROCESS, 10 * eventsPerProcess);

        CountDownLatch startTogether = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        Thread mainThread = recordingThread(main, "main", eventsPerProcess, startTogether, finished);
        Thread serviceThread = recordingThread(service, "service", eventsPerProcess, startTogether, finished);
        mainThread.start();
        serviceThread.start();
        startTogether.countDown();
        assertTrue(finished.await(20, TimeUnit.SECONDS));

        // Every event either process recorded is readable, and every frame parses - a spliced frame would
        // show up as a parse failure or a missing event, because a torn frame ends the reader.
        Set<String> everything = new HashSet<>(visibleKeys(main));
        everything.addAll(visibleKeys(service));
        assertEquals(2 * eventsPerProcess, everything.size());
        for (int i = 0; i < eventsPerProcess; i++) {
            assertTrue("lost main-" + i, everything.contains("main-" + i));
            assertTrue("lost service-" + i, everything.contains("service-" + i));
        }
    }

    private Thread recordingThread(
            EventStore store,
            String prefix,
            int count,
            CountDownLatch startTogether,
            CountDownLatch finished
    ) {
        return new Thread(() -> {
            try {
                startTogether.await();
                for (int i = 0; i < count; i++) {
                    store.stage(event(prefix + "-" + i));
                    // Committing on every event maximizes the chance of two writes overlapping, which is
                    // the interleaving this is looking for.
                    store.commit();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        });
    }
}
