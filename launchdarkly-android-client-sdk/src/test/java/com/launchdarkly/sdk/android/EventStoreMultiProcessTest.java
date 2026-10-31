package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * The event store's behavior when an application runs the SDK in more than one process.
 * <p>
 * An Android application may declare components in another process, and each process gets its own copy of
 * the SDK sharing nothing but the filesystem. From the store's point of view a second process is simply a
 * second instance over the same directory, which is what these tests set up. That is not the same thing as a second OS process - it cannot reproduce a genuinely concurrent
 * {@code O_APPEND} write from separate address spaces, which needs an instrumented test - but it does
 * exercise every decision the design makes about sharing: which files a process may touch, which it must
 * leave alone, and what happens when both reach for the same batch.
 */
public class EventStoreMultiProcessTest {
    private static final int CAPACITY = 100;

    @Rule
    public Timeout globalTimeout = Timeout.seconds(30);
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private final LDLogger logger = LDLogger.withAdapter(Logs.none(), "");

    private File sharedDirectory() {
        return new File(tempFolder.getRoot(), "events");
    }

    /** A store over the shared directory: another process, or another run of one. */
    private EventStore storeFor() {
        return storeFor(CAPACITY);
    }

    private EventStore storeFor(int capacity) {
        return new EventStore(sharedDirectory(), capacity, true, logger);
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

    private byte[] readAll(File file) throws IOException {
        return java.nio.file.Files.readAllBytes(file.toPath());
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

    // ---- each process writes only its own log ----

    @Test
    public void eachProcessAppendsToItsOwnLog() {
        EventStore main = storeFor();
        EventStore service = storeFor();

        main.stage(event("main-1"));
        main.commit();
        service.stage(event("service-1"));
        service.commit();
        main.stage(event("main-2"));
        main.commit();

        // Two logs, not one. This is the property that makes a spliced frame impossible: only one
        // process ever has the file open for writing.
        assertEquals(2, filesNamed(EventDirectory.LOG_PREFIX).size());
        assertEquals(Arrays.asList("main-1", "main-2"), visibleKeys(main));
        assertEquals(Arrays.asList("service-1"), visibleKeys(service));
    }

    @Test
    public void aLogIsNamedForAUuid() {
        EventStore main = storeFor();
        main.stage(event("main-1"));
        main.commit();

        List<File> logs = filesNamed(EventDirectory.LOG_PREFIX);
        assertEquals(1, logs.size());
        String uuid = logs.get(0).getName().substring(EventDirectory.LOG_PREFIX.length());
        assertEquals(uuid, UUID.fromString(uuid).toString());
        assertTrue("a log was left under the name it is opened under", filesNamed("new-").isEmpty());
    }

    // ---- recovery takes what no live process holds ----

    /**
     * What the kernel does to a store's log when its process dies: closes the descriptor, which releases the
     * lock on it. Everything the store staged has been committed by then.
     */
    private static void processDies(EventStore store) {
        store.close();
    }

    @Test
    public void recoveryLeavesAnotherProcessOpenLogAlone() {
        EventStore service = storeFor();
        service.stage(event("service-1"));
        service.commit();

        // The main process starts while the service process is still alive and still appending. Its
        // recovery must not take the log out from under it.
        EventStore main = storeFor();
        main.preparePayloadsFromInterruptedLogs();

        assertEquals("the other process's log was taken away", 1, filesNamed(EventDirectory.LOG_PREFIX).size());
        assertTrue("another process's events were claimed as recovered", main.pendingBatches().isEmpty());

        // And the service process can still append to it and read back both events.
        service.stage(event("service-2"));
        service.commit();
        assertEquals(Arrays.asList("service-1", "service-2"), visibleKeys(service));
    }

    @Test
    public void recoveryLeavesALogWhoseLockIsHeldAlone() throws IOException {
        EventStore service = storeFor();
        service.stage(event("service-1"));
        service.commit();
        processDies(service);
        File log = filesNamed(EventDirectory.LOG_PREFIX).get(0);

        // A process this one knows nothing about, holding the lock on the log as its owner does.
        try (RandomAccessFile owner = new RandomAccessFile(log, "rw");
             FileLock held = owner.getChannel().lock()) {
            EventStore main = storeFor();
            main.preparePayloadsFromInterruptedLogs();
            assertTrue("a log a live process holds was recovered", main.pendingBatches().isEmpty());
            assertTrue(log.exists());
        }

        EventStore main = storeFor();
        main.preparePayloadsFromInterruptedLogs();
        assertEquals(1, main.pendingBatches().size());
    }

    @Test
    public void anyProcessRecoversALogWhoseWriterIsGone() {
        EventStore service = storeFor();
        service.stage(event("before-the-crash"));
        service.commit();
        processDies(service);

        // Not the process that wrote it: a service process may never run again.
        EventStore main = storeFor();
        main.preparePayloadsFromInterruptedLogs();

        List<EventStore.Batch> recovered = main.pendingBatches();
        assertEquals(1, recovered.size());
        assertEquals(1, recovered.get(0).eventCount);
        assertEquals(Arrays.asList("before-the-crash"), visibleKeys(main));
        assertTrue("the recovered log should no longer be open", filesNamed(EventDirectory.LOG_PREFIX).isEmpty());
    }

    @Test
    public void recoveryTakesEveryLogLeftBehind() {
        EventStore firstRun = storeFor();
        firstRun.stage(event("first-run"));
        firstRun.commit();
        processDies(firstRun);
        EventStore service = storeFor();
        service.stage(event("service-1"));
        service.stage(event("service-2"));
        service.commit();
        processDies(service);

        EventStore secondRun = storeFor();
        secondRun.preparePayloadsFromInterruptedLogs();

        assertEquals(2, secondRun.pendingBatches().size());
        assertEquals(3, secondRun.getPendingEventCount());
        assertTrue(filesNamed(EventDirectory.LOG_PREFIX).isEmpty());
    }

    @Test
    public void aCommitBeforeStartupRecoveryKeepsThisRunsEventsOutOfWhatItRecovers() {
        // With IMMEDIATE the first commit happens on the caller's thread, and can beat the recovery the
        // processor queues on its own thread at startup.
        EventStore firstRun = storeFor();
        firstRun.stage(event("previous-run"));
        firstRun.commit();
        processDies(firstRun);

        EventStore secondRun = storeFor();
        secondRun.stage(event("this-run"));
        secondRun.commit();
        secondRun.preparePayloadsFromInterruptedLogs();

        List<EventStore.Batch> recovered = secondRun.pendingBatches();
        assertEquals(1, recovered.size());
        assertEquals("this run's event was counted as a previous run's", 1, recovered.get(0).eventCount);
        assertEquals(1, filesNamed(EventDirectory.LOG_PREFIX).size());
        assertEquals(Arrays.asList("previous-run", "this-run"), visibleKeys(secondRun));
    }

    @Test
    public void aStoreThatClosedItsLogAppendsToANewOne() {
        EventStore store = storeFor();
        store.stage(event("before-close"));
        store.commit();
        File first = filesNamed(EventDirectory.LOG_PREFIX).get(0);
        store.close();

        // Unlocked once closed, and so another process's to recover from then on.
        store.stage(event("after-close"));
        store.commit();

        assertEquals(2, filesNamed(EventDirectory.LOG_PREFIX).size());
        assertTrue(first.exists());
        assertEquals(Arrays.asList("after-close"), visibleKeys(store));
    }

    @Test
    public void aStoreWithoutPersistenceLeavesWhatAPersistentRunLeftBehindAlone() {
        EventStore persistentRun = storeFor();
        persistentRun.stage(event("closed-batch"));
        closeOne(persistentRun);
        persistentRun.stage(event("open-log"));
        persistentRun.commit();
        processDies(persistentRun);

        EventStore withoutPersistence =
                new EventStore(sharedDirectory(), CAPACITY, false, logger);
        withoutPersistence.preparePayloadsFromInterruptedLogs();
        assertTrue("a store without persistence picked up a previous run's events",
                withoutPersistence.pendingBatches().isEmpty());
        assertTrue(visibleKeys(withoutPersistence).isEmpty());

        withoutPersistence.stage(event("in-memory"));
        EventStore.Batch own = closeOne(withoutPersistence);
        assertNotNull(own);
        assertEquals(Arrays.asList(own), withoutPersistence.pendingBatches());

        assertEquals(1, filesNamed("ready-").size());
        assertEquals(1, filesNamed(EventDirectory.LOG_PREFIX).size());

        // Still there for the next run that has persistence turned on.
        EventStore persistentAgain = storeFor();
        persistentAgain.preparePayloadsFromInterruptedLogs();
        assertEquals(2, persistentAgain.pendingBatches().size());
    }

    @Test
    public void aStoreWithoutPersistenceAsksTheFilesystemNothingEvenAboutABatchItDoesNotHold()
            throws IOException {
        AtomicInteger reads = new AtomicInteger();
        EventStore store = new EventStore(sharedDirectory(), CAPACITY, false, logger) {
            @Override
            FileInputStream openForReading(File file) throws IOException {
                reads.incrementAndGet();
                return super.openForReading(file);
            }
        };

        store.stage(event("held"));
        EventStore.Batch held = closeOne(store);
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
        EventStore service = storeFor();
        service.stage(event("service-1"));
        EventStore.Batch fromService = closeOne(service);
        assertNotNull(fromService);

        // Recovery runs when a store is constructed, before that process has recorded anything of its own,
        // which is the only time it is correct to go looking for a log to close.
        EventStore main = storeFor();
        main.preparePayloadsFromInterruptedLogs();
        main.stage(event("main-1"));
        main.commit();

        // The service process's batch is untouched and still deliverable, by either of them.
        assertEquals(1, filesNamed("ready-").size());
        assertTrue(main.pendingBatches().contains(fromService));
    }

    // ---- closed batches are shared on purpose ----

    @Test
    public void aBatchClosedByOneProcessIsDeliverableByAnother() throws IOException {
        // The point of sharing: a service process that records events and is then killed may never run
        // again, so a batch only it could see would never be delivered.
        EventStore service = storeFor();
        service.stage(event("recorded-by-the-service"));
        EventStore.Batch batch = closeOne(service);
        assertNotNull(batch);

        EventStore main = storeFor();
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
    public void aClosedBatchIsCountedFromItsNameWithoutReadingItBack() throws IOException {
        EventStore main = storeFor();
        main.stage(event("first"));
        main.stage(event("second"));
        EventStore.Batch batch = closeOne(main);
        assertNotNull(batch);

        // Nothing appends to a batch once it is closed, so the count its name carries still holds and
        // listing it has no reason to go to the disk. Damaging the file is how the test tells the two
        // apart: a listing that read would refuse this batch.
        List<File> ready = filesNamed("ready-");
        assertEquals(1, ready.size());
        assertEquals("ready-v1-" + batch.payloadId + ".2", ready.get(0).getName());
        overwrite(ready.get(0), "not an event log".getBytes(Charset.forName("UTF-8")));

        // The process that closed it and one that did not both count it from the name alone.
        for (EventStore store : Arrays.asList(main, storeFor())) {
            List<EventStore.Batch> pending = store.pendingBatches();
            assertEquals(1, pending.size());
            assertEquals(batch.payloadId, pending.get(0).payloadId);
            assertEquals(2, pending.get(0).eventCount);
        }

        // The damage is found where the batch is read to be sent, and reads as a loss rather than as a
        // failure to retry, so the delivery removes it instead of trying it forever, and says it lost events.
        try {
            main.body(batch);
            fail("a damaged batch read as something to send or as nothing to lose");
        } catch (EventStore.UnreadableBatchException expected) {
            // what a delivery takes as lost
        }
    }

    @Test
    public void aBatchWhoseFirstFrameIsDamagedIsLostRatherThanEmpty() throws IOException {
        EventStore main = storeFor();
        main.stage(event("damaged"));
        EventStore.Batch batch = closeOne(main);
        assertNotNull(batch);

        File file = filesNamed("ready-").get(0);
        byte[] damaged = readAll(file);
        damaged[EventLogFormat.FILE_HEADER_SIZE + EventLogFormat.FRAME_HEADER_SIZE] ^= 0x01;
        overwrite(file, damaged);

        try {
            main.body(batch);
            fail("a batch with a damaged frame read as nothing to lose");
        } catch (EventStore.UnreadableBatchException expected) {
            // what a delivery takes as lost
        }
    }

    @Test
    public void aBatchNamedWithoutACountWhoseFirstFrameIsDamagedIsListedAsOneEventAndLost() throws IOException {
        EventStore previousVersion = storeFor();
        previousVersion.stage(event("damaged"));
        closeOne(previousVersion);
        File file = asUncountedName(filesNamed("ready-").get(0));
        byte[] damaged = readAll(file);
        damaged[EventLogFormat.FILE_HEADER_SIZE + EventLogFormat.FRAME_HEADER_SIZE] ^= 0x01;
        overwrite(file, damaged);

        // Counted as no events, it would be dropped as a loss of none, which no flush hears about.
        EventStore main = storeFor();
        List<EventStore.Batch> pending = main.pendingBatches();
        assertEquals(1, pending.size());
        assertEquals("the fewest it can hold", 1, pending.get(0).eventCount);
        try {
            main.body(pending.get(0));
            fail("a batch with a damaged frame read as nothing to lose");
        } catch (EventStore.UnreadableBatchException expected) {
            // what a delivery takes as lost
        }
    }

    /**
     * Renames a batch to a name without a count, which is what a recovery that could not read a log
     * leaves behind for a listing to count.
     */
    private File asUncountedName(File batch) {
        String name = batch.getName();
        File renamed = new File(batch.getParentFile(), name.substring(0, name.lastIndexOf('.')));
        assertTrue("could not rename " + name, batch.renameTo(renamed));
        return renamed;
    }

    @Test
    public void aBatchNamedWithoutACountIsReadOnceAndThenRemembered() throws IOException {
        EventStore previousVersion = storeFor();
        previousVersion.stage(event("left-by-a-previous-version"));
        closeOne(previousVersion);
        assertEquals(1, filesNamed("ready-").size());
        asUncountedName(filesNamed("ready-").get(0));

        AtomicInteger reads = new AtomicInteger();
        EventStore main = new EventStore(sharedDirectory(), CAPACITY, true, logger) {
            @Override
            FileInputStream openForReading(File file) throws IOException {
                reads.incrementAndGet();
                return super.openForReading(file);
            }
        };

        List<EventStore.Batch> pending = main.pendingBatches();
        assertEquals(1, pending.size());
        assertEquals(1, pending.get(0).eventCount);
        assertEquals("a batch with no count in its name was not read", 1, reads.get());

        // Counted once and remembered, rather than read again by every listing for the rest of the session.
        assertEquals(pending, main.pendingBatches());
        assertEquals(1, reads.get());

        // And still deliverable under the name it has, which is not the one its count would give it.
        assertNotNull(main.body(pending.get(0)));
        main.remove(pending.get(0));
        assertTrue(filesNamed("ready-").isEmpty());
    }

    @Test
    public void aPayloadIdEndingInZeroLedDigitsIsNotTakenForACount() {
        EventStore previousVersion = storeFor();
        previousVersion.stage(event("left-by-a-previous-version"));
        closeOne(previousVersion);
        File batch = filesNamed("ready-").get(0);
        // Its last group fits an int once the leading zeros are dropped.
        String payloadId = "6F9619FF-8B86-D011-B42D-000012345678";
        assertTrue(batch.renameTo(new File(batch.getParentFile(), "ready-v1-" + payloadId)));

        List<EventStore.Batch> pending = storeFor().pendingBatches();
        assertEquals(1, pending.size());
        assertEquals(payloadId, pending.get(0).payloadId);
        assertEquals(1, pending.get(0).eventCount);
    }

    /** A batch as a later version might name it, holding events this version would otherwise read. */
    private File asAnotherVersionsBatch() {
        EventStore later = storeFor();
        later.stage(event("first"));
        later.stage(event("second"));
        closeOne(later);
        later.close();
        File file = filesNamed("ready-").get(0);
        // Fields this version would take for a payload ID and a count, were the name its own.
        File renamed = new File(file.getParentFile(), "ready-v2-7." + UUID.randomUUID());
        assertTrue(file.renameTo(renamed));
        return renamed;
    }

    @Test
    public void aBatchAnotherVersionNamedIsNeitherCountedNorSent() {
        File batch = asAnotherVersionsBatch();

        EventStore store = storeFor();
        assertTrue(store.pendingBatches().isEmpty());
        assertEquals(0, store.getPendingEventCount());
        assertTrue(batch.exists());
    }

    @Test
    public void anExpiredBatchAnotherVersionNamedIsDeletedWithoutBeingCounted() {
        File batch = asAnotherVersionsBatch();
        assertTrue(batch.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)));

        assertEquals(0, storeFor().discardExpired());
        assertFalse(batch.exists());
    }

    @Test
    public void aNameWithAFieldAfterTheCountIsReadByPosition() throws IOException {
        EventStore later = storeFor();
        later.stage(event("first"));
        later.stage(event("second"));
        EventStore.Batch batch = closeOne(later);
        File file = filesNamed("ready-").get(0);
        // What a later version might add, which this one has no use for.
        assertTrue(file.renameTo(new File(file.getParentFile(), file.getName() + ".v2-something")));

        List<EventStore.Batch> pending = storeFor().pendingBatches();
        assertEquals(1, pending.size());
        assertEquals(batch.payloadId, pending.get(0).payloadId);
        assertEquals(2, pending.get(0).eventCount);
    }

    @Test
    public void aCountThatDoesNotParseIsCountedByReadingTheBatch() {
        EventStore previous = storeFor();
        previous.stage(event("only"));
        EventStore.Batch batch = closeOne(previous);
        File file = asUncountedName(filesNamed("ready-").get(0));
        assertTrue(file.renameTo(new File(file.getParentFile(), file.getName() + ".not-a-count")));

        List<EventStore.Batch> pending = storeFor().pendingBatches();
        assertEquals(1, pending.size());
        assertEquals(batch.payloadId, pending.get(0).payloadId);
        assertEquals(1, pending.get(0).eventCount);
    }

    @Test
    public void aBatchTheOtherProcessAlreadyDeliveredReadsAsNothingToSend() throws IOException {
        EventStore service = storeFor();
        service.stage(event("delivered-once"));
        EventStore.Batch batch = closeOne(service);
        assertNotNull(batch);

        EventStore main = storeFor();
        main.remove(batch); // main delivered it first

        // The other process asking for the body gets nothing, which its caller treats as already
        // delivered rather than as a failure to retry.
        assertNull(service.body(batch));
    }

    @Test
    public void bothProcessesRemovingTheSameBatchIsHarmless() {
        EventStore service = storeFor();
        service.stage(event("delivered-twice"));
        EventStore.Batch batch = closeOne(service);
        assertNotNull(batch);

        EventStore main = storeFor();
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
        EventStore service = storeFor();
        for (int i = 0; i < 3; i++) {
            service.stage(event("service-" + i));
        }
        closeOne(service);

        EventStore main = storeFor();
        main.pendingBatches(); // this is what reconciles the count with the directory

        assertEquals(3, main.getPendingEventCount());
    }

    // ---- a read that fails is no reason to delete ----

    @Test
    public void recoveryReadsWhatAPreviousRunLeftWithoutHoldingUpACommit() throws Exception {
        // At IMMEDIATE a track commits on the caller's thread, and recovery runs at startup; a crash can
        // leave many logs of up to a batch each.
        EventStore firstRun = storeFor();
        firstRun.stage(event("before-the-crash"));
        firstRun.commit();
        processDies(firstRun);

        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch readResumes = new CountDownLatch(1);
        EventStore secondRun = new EventStore(sharedDirectory(), CAPACITY, true, logger) {
            @Override
            FileInputStream openForReading(File file) throws IOException {
                if (file.getName().startsWith(EventDirectory.LOG_PREFIX)) {
                    readStarted.countDown();
                    try {
                        readResumes.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.openForReading(file);
            }
        };
        ExecutorService recovery = Executors.newSingleThreadExecutor();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> recovered = recovery.submit(secondRun::preparePayloadsFromInterruptedLogs);
            assertTrue(readStarted.await(5, TimeUnit.SECONDS));

            Future<?> committed = caller.submit(() -> {
                secondRun.stage(event("this-run"));
                secondRun.commit();
            });
            committed.get(2, TimeUnit.SECONDS);

            readResumes.countDown();
            recovered.get(5, TimeUnit.SECONDS);
            assertEquals(1, secondRun.pendingBatches().size());
            assertEquals(new HashSet<>(Arrays.asList("before-the-crash", "this-run")),
                    new HashSet<>(visibleKeys(secondRun)));
        } finally {
            readResumes.countDown();
            recovery.shutdownNow();
            caller.shutdownNow();
        }
    }

    /**
     * A store whose reads fail while {@code failing} is set, the way they do in a process that has run
     * out of file descriptors: the files are intact and readable again once some are freed.
     */
    private EventStore storeWithFailingReads(AtomicBoolean failing) {
        return new EventStore(sharedDirectory(), CAPACITY, true, logger) {
            @Override
            FileInputStream openForReading(File file) throws IOException {
                if (failing.get()) {
                    throw new FileNotFoundException(file + " (Too many open files)");
                }
                return super.openForReading(file);
            }
        };
    }

    @Test
    public void aBatchNamedWithoutACountThatCannotBeReadIsLeftForALaterListing() {
        EventStore service = storeFor();
        service.stage(event("recorded-by-the-service"));
        closeOne(service);
        asUncountedName(filesNamed("ready-").get(0));

        AtomicBoolean failing = new AtomicBoolean(true);
        EventStore main = storeWithFailingReads(failing);
        assertTrue(main.pendingBatches().isEmpty());
        assertEquals("a batch that could not be read was deleted", 1, filesNamed("ready-").size());

        failing.set(false);
        List<EventStore.Batch> pending = main.pendingBatches();
        assertEquals(1, pending.size());
        assertEquals(1, pending.get(0).eventCount);
    }

    @Test
    public void aBatchCountedByItsNameIsListedEvenWhileReadsAreFailing() {
        EventStore service = storeFor();
        service.stage(event("recorded-by-the-service"));
        closeOne(service);

        // The read a listing used to need is the one a process out of file descriptors cannot make. With
        // the count in the name there is nothing to read until the batch is sent, and a body that cannot
        // be read is a delivery to retry rather than a batch that is invisible until it can be.
        AtomicBoolean failing = new AtomicBoolean(true);
        EventStore main = storeWithFailingReads(failing);
        List<EventStore.Batch> pending = main.pendingBatches();
        assertEquals(1, pending.size());
        assertEquals(1, pending.get(0).eventCount);
        try {
            main.body(pending.get(0));
            fail("a batch that could not be read was reported as having nothing to send");
        } catch (IOException expected) {
            // what the processor retries on
        }
    }

    @Test
    public void aBodyThatCannotBeReadIsAFailureToRetryRatherThanNothingToSend() throws IOException {
        AtomicBoolean failing = new AtomicBoolean(false);
        EventStore main = storeWithFailingReads(failing);
        main.stage(event("kept"));
        EventStore.Batch batch = closeOne(main);
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
        EventStore firstRun = storeFor();
        firstRun.stage(event("before-the-crash"));
        firstRun.commit();
        processDies(firstRun);

        AtomicBoolean failing = new AtomicBoolean(true);
        EventStore secondRun = storeWithFailingReads(failing);
        secondRun.preparePayloadsFromInterruptedLogs();
        // Closed off uncounted for a listing to count, and not deleted.
        assertTrue(filesNamed(EventDirectory.LOG_PREFIX).isEmpty());
        assertEquals(1, filesNamed("ready-").size());

        secondRun.stage(event("this-run"));
        secondRun.commit();
        failing.set(false);
        List<EventStore.Batch> pending = secondRun.pendingBatches();
        assertEquals(1, pending.size());
        assertEquals(1, pending.get(0).eventCount);
        assertEquals(Arrays.asList("before-the-crash", "this-run"), visibleKeys(secondRun));
    }

    // ---- a write that fails partway ----

    /**
     * A store whose writes land whole until {@code whole} of them have, after which each gets all but
     * the last few bytes onto the disk and fails, as one does when the device fills mid-write.
     */
    private EventStore storeFailingPartwayAfter(int whole) {
        AtomicInteger writes = new AtomicInteger();
        return new EventStore(sharedDirectory(), CAPACITY, true, logger) {
            @Override
            FileOutputStream openForAppending(File file) throws IOException {
                return new FileOutputStream(file, true) {
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

    @Test
    public void eventsBeingWrittenStillCountAgainstCapacity() throws Exception {
        // A commit takes the staged events out of the buffer and writes them outside the buffer's lock.
        // They used to be counted nowhere until the write returned, and capacity admitted that many more.
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch letWriteFinish = new CountDownLatch(1);
        EventStore store = new EventStore(sharedDirectory(), 2, true, logger) {
            @Override
            FileOutputStream openForAppending(File file) throws IOException {
                writing.countDown();
                try {
                    letWriteFinish.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.openForAppending(file);
            }
        };
        assertTrue(store.stage(event("first"), false));
        assertTrue(store.stage(event("second"), false));

        Thread committer = new Thread(store::commit);
        committer.start();
        try {
            assertTrue("the commit never reached the disk", writing.await(5, TimeUnit.SECONDS));

            assertEquals(2, store.getPendingEventCount());
            assertFalse("admitted past capacity while the write was under way",
                    store.stage(event("third"), false));
        } finally {
            letWriteFinish.countDown();
            committer.join(5_000);
        }

        assertEquals(2, store.getPendingEventCount());
        assertFalse(store.stage(event("third"), false));
        assertEquals(Arrays.asList("first", "second"), visibleKeys(store));
    }

    @Test
    public void removingABatchTwiceStopsCountingItOnce() {
        // Deliveries can overlap, and two of them can both get to the same batch.
        for (boolean persist : new boolean[] {true, false}) {
            EventStore store = new EventStore(new File(sharedDirectory(), "persist-" + persist),
                    CAPACITY, persist, logger);
            store.stage(event("first"));
            EventStore.Batch first = closeOne(store);
            store.stage(event("second"));
            store.closeBatch();
            store.pendingBatches();

            store.remove(first);
            store.remove(first);

            assertEquals("persisting: " + persist, 1, store.getPendingEventCount());
        }
    }

    @Test
    public void aDeliveredBatchWhoseFileWillNotDeleteIsNotListedAgain() {
        EventStore store = storeFor();
        store.stage(event("first"));
        EventStore.Batch first = closeOne(store);
        store.stage(event("second"));
        EventStore.Batch second = closeOne(store);
        assertNotNull(first);
        assertNotNull(second);

        File directory = sharedDirectory();
        // A directory with no write permission is one its entries cannot be unlinked from, which is what the
        // filesystem refusing a delete looks like from here.
        assertTrue("could not make the directory read-only", directory.setWritable(false));
        try {
            store.remove(first);

            // Delivered, so it neither counts against capacity nor comes back for another delivery, even
            // though its file is still sitting there.
            assertEquals(Arrays.asList(second), store.pendingBatches());
            assertEquals(1, store.getPendingEventCount());
        } finally {
            assertTrue("could not make the directory writable again", directory.setWritable(true));
        }

        // The listing that can delete it does, rather than leaving it for a run that would send it again.
        store.pendingBatches();
        assertTrue(filesNamed("ready-v1-" + first.payloadId).isEmpty());
    }

    @Test
    public void aLogThatCouldNotBeClosedStaysOpenAndIsClosedWholeByTheNextClose() throws IOException {
        EventStore store = storeFor();
        store.stage(event("first"));
        store.stage(event("second"));
        store.commit();

        File directory = sharedDirectory();
        // A directory with no write permission is one its entries cannot be renamed in, which is what the
        // filesystem refusing to close the log looks like from here.
        assertTrue("could not make the directory read-only", directory.setWritable(false));
        try {
            assertTrue("closed a log with the directory read-only", store.closeBatch().isEmpty());
        } finally {
            assertTrue("could not make the directory writable again", directory.setWritable(true));
        }
        assertEquals(2, store.getPendingEventCount());

        // Appended to the same log, so one batch holds all three and is named for as many.
        store.stage(event("third"));
        EventStore.Batch closed = closeOne(store);
        assertEquals(3, closed.eventCount);
        assertEquals(Arrays.asList("first", "second", "third"), keysIn(store.body(closed)));
        assertTrue("a log was left behind", filesNamed("pending-").isEmpty());

        List<EventStore.Batch> listed = storeFor().pendingBatches();
        assertEquals(1, listed.size());
        assertEquals(3, listed.get(0).eventCount);
    }

    @Test
    public void aBatchAnotherProcessDeliveredStopsCountingOnce() {
        EventStore main = storeFor();
        EventStore service = storeFor();
        main.stage(event("first"));
        EventStore.Batch first = closeOne(main);
        main.stage(event("second"));
        main.closeBatch();

        // The other process delivers it, and this one lists again before its own delivery of it finishes.
        service.remove(first);
        main.pendingBatches();
        main.remove(first);

        assertEquals(1, main.getPendingEventCount());
    }

    @Test
    public void aListingDoesNotLoseOrDoubleABatchClosedWhileItRuns() throws Exception {
        for (boolean persist : new boolean[] {true, false}) {
            EventStore store = new EventStore(new File(sharedDirectory(), "persist-" + persist),
                    100_000, persist, logger);
            AtomicBoolean done = new AtomicBoolean();
            Thread lister = new Thread(() -> {
                while (!done.get()) {
                    store.pendingBatches();
                    // Paced, or the listings hold the locks so continuously that the closes starve: a monitor is
                    // not fair, and a yield returns at once when the closing thread is blocked on it rather than
                    // runnable. A listing still overlaps every close.
                    LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(200));
                }
            });
            lister.start();
            try {
                // Checked after every close, because the next listing would correct a miscount before
                // the end of the loop could see it.
                for (int i = 1; i <= 150; i++) {
                    store.stage(event("event-" + i));
                    store.closeBatch();
                    assertEquals("persisting: " + persist, i, store.getPendingEventCount());
                }
            } finally {
                done.set(true);
                lister.join(5_000);
            }
        }
    }

    /** Closes what a test expects to be exactly one batch, as anything short of a failed write leaves. */
    private static EventStore.Batch closeOne(EventStore store) {
        List<EventStore.Batch> closed = store.closeBatch();
        assertEquals(1, closed.size());
        return closed.get(0);
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

        List<EventStore.Batch> closed = store.closeBatch();
        assertEquals("the log's batch and memory's, both reported", 2, closed.size());
        assertEquals(Arrays.asList("written"), keysIn(store.body(closed.get(0))));
        assertEquals(Arrays.asList("landed", "torn"), keysIn(store.body(closed.get(1))));

        List<List<String>> bodies = new ArrayList<>();
        for (EventStore.Batch batch : store.pendingBatches()) {
            bodies.add(keysIn(store.body(batch)));
        }
        assertEquals(Arrays.asList(Arrays.asList("written"), Arrays.asList("landed", "torn")), bodies);
    }

    @Test
    public void summariesAFailedWriteLeftInMemoryStillDoNotCountAsEvents() throws IOException {
        // The file header and the first commit land; the second fails partway into its summary.
        EventStore store = storeFailingPartwayAfter(2);
        store.stage(event("written"));
        store.stage(event("summary-written"), true);
        store.commit();
        store.stage(event("landed"));
        store.stage(event("summary-put-back"), true);
        store.commit();
        assertFalse(store.isPersisting());
        assertEquals(2, store.getPendingEventCount());

        List<EventStore.Batch> closed = store.closeBatch();
        assertEquals(2, closed.size());
        assertEquals(Arrays.asList("written", "summary-written"), keysIn(store.body(closed.get(0))));
        assertEquals(Arrays.asList("landed", "summary-put-back"), keysIn(store.body(closed.get(1))));
        assertEquals(2, store.getPendingEventCount());
        store.pendingBatches();
        assertEquals(2, store.getPendingEventCount());
    }

    @Test
    public void sendsNoFramesAWriteThatFailedLeftBehindWhenItCouldNotBeCutBack() throws IOException {
        EventStore store = storeFor();
        store.stage(event("written"));
        EventStore.Batch closed = closeOne(store);
        File[] files = sharedDirectory().listFiles((dir, name) -> name.startsWith("ready-"));
        assertNotNull(files);
        assertEquals(1, files.length);
        // Whole frames past the one the batch was closed holding, which memory delivers instead.
        ByteArrayOutputStream leftBehind = new ByteArrayOutputStream();
        EventLogFormat.writeFrame(leftBehind, event("from-memory"));
        try (FileOutputStream log = new FileOutputStream(files[0], true)) {
            log.write(leftBehind.toByteArray());
        }

        assertEquals(Arrays.asList("written"), keysIn(store.body(closed)));
        EventStore other = storeFor();
        List<EventStore.Batch> listed = other.pendingBatches();
        assertEquals(1, listed.size());
        assertEquals(1, listed.get(0).eventCount);
        assertEquals(Arrays.asList("written"), keysIn(other.body(listed.get(0))));
    }

    @Test
    public void theNextRunHasNothingToRecoverFromAFirstWriteThatFailedPartway() throws IOException {
        // The file header lands, and then a whole frame and part of another before the device is full.
        EventStore store = storeFailingPartwayAfter(1);

        store.stage(event("landed"));
        store.stage(event("torn"));
        store.commit();
        EventStore.Batch fromMemory = closeOne(store);
        assertNotNull(fromMemory);
        assertEquals(Arrays.asList("landed", "torn"), keysIn(store.body(fromMemory)));

        // Delivered from memory this run, so the next must not find any of it again in the log.
        EventStore nextRun = storeFor();
        nextRun.preparePayloadsFromInterruptedLogs();
        assertEquals(0, nextRun.pendingBatches().size());
    }

    @Test
    public void theNextRunHasNothingToRecoverFromAFirstWriteThatFailedPartwayAndCouldNotBeCutBack()
            throws IOException {
        // As above, but the whole frame the write left in the log stays there.
        AtomicInteger writes = new AtomicInteger();
        EventStore store = new EventStore(sharedDirectory(), CAPACITY, true, logger) {
            @Override
            FileOutputStream openForAppending(File file) throws IOException {
                return new FileOutputStream(file, true) {
                    @Override
                    public void write(byte[] bytes) throws IOException {
                        if (writes.incrementAndGet() <= 1) {
                            super.write(bytes);
                            return;
                        }
                        super.write(bytes, 0, bytes.length - 3);
                        throw new IOException("No space left on device");
                    }

                    @Override
                    public FileChannel getChannel() {
                        return new RefusingToTruncate(super.getChannel());
                    }
                };
            }
        };

        store.stage(event("landed"));
        store.stage(event("torn"));
        store.commit();
        assertFalse(store.isPersisting());
        EventStore.Batch fromMemory = closeOne(store);
        assertEquals(Arrays.asList("landed", "torn"), keysIn(store.body(fromMemory)));
        store.remove(fromMemory);
        processDies(store);

        EventStore nextRun = storeFor();
        nextRun.preparePayloadsFromInterruptedLogs();
        List<String> recovered = new ArrayList<>();
        for (EventStore.Batch batch : nextRun.pendingBatches()) {
            recovered.addAll(keysIn(nextRun.body(batch)));
        }
        assertEquals("delivered twice", new ArrayList<String>(), recovered);
    }

    /** A channel that does everything the one it wraps does, except truncate, as a device that refuses it. */
    private static final class RefusingToTruncate extends FileChannel {
        private final FileChannel channel;

        RefusingToTruncate(FileChannel channel) {
            this.channel = channel;
        }

        @Override
        public FileChannel truncate(long size) throws IOException {
            throw new IOException("I/O error");
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return channel.read(dst);
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            return channel.read(dsts, offset, length);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            return channel.write(src);
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            return channel.write(srcs, offset, length);
        }

        @Override
        public long position() throws IOException {
            return channel.position();
        }

        @Override
        public FileChannel position(long newPosition) throws IOException {
            channel.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return channel.size();
        }

        @Override
        public void force(boolean metaData) throws IOException {
            channel.force(metaData);
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
            return channel.transferTo(position, count, target);
        }

        @Override
        public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
            return channel.transferFrom(src, position, count);
        }

        @Override
        public int read(ByteBuffer dst, long position) throws IOException {
            return channel.read(dst, position);
        }

        @Override
        public int write(ByteBuffer src, long position) throws IOException {
            return channel.write(src, position);
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            return channel.map(mode, position, size);
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) throws IOException {
            return channel.lock(position, size, shared);
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            return channel.tryLock(position, size, shared);
        }

        @Override
        protected void implCloseChannel() throws IOException {
            channel.close();
        }
    }

    // ---- the two together ----

    @Test
    public void concurrentRecordingFromTwoProcessesLosesAndCorruptsNothing() throws Exception {
        int eventsPerProcess = 200;
        // Capacity well clear of what the test records, so that a dropped event means a real loss rather
        // than the store correctly refusing to exceed its limit.
        EventStore main = storeFor(10 * eventsPerProcess);
        EventStore service = storeFor(10 * eventsPerProcess);

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
