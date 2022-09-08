package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import org.junit.Rule;
import org.junit.Test;

public class ContextDecoratorTest {
    private static final ContextKind KIND1 = ContextKind.of("kind1");
    private static final ContextKind KIND2 = ContextKind.of("kind2");

    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    @Test
    public void singleKindNonAnonymousContextIsUnchanged() {
        LDContext context = LDContext.builder("key1").name("name").build();
        assertEquals(context,
                makeDecoratorWithoutPersistence().decorateContext(context, logging.logger));
        logging.assertNothingLogged();
    }

    @Test
    public void singleKindAnonymousContextIsUnchangedIfConfigOptionIsNotSet() {
        LDContext context = LDContext.builder("key1").anonymous(true).name("name").build();
        assertEquals(context,
                makeDecoratorWithoutPersistence().decorateContext(context, logging.logger));
        logging.assertNothingLogged();
    }

    @Test
    public void singleKindAnonymousContextGetsGeneratedKeyIfConfigOptionIsSet() {
        LDContext context = LDContext.builder("placeholder").anonymous(true).name("name").build();
        LDContext transformed = makeDecoratorWithoutPersistence(true)
                .decorateContext(context, logging.logger);
        assertContextHasBeenTransformedWithNewKey(context, transformed);
        logging.assertInfoLogged("Did not find a generated anonymous key for context kind \"user\"");
    }

    @Test
    public void multiKindContextIsUnchangedIfNoIndividualContextsNeedGeneratedKey() {
        LDContext c1 = LDContext.builder("key1").kind(KIND1).name("name1").build();
        LDContext c2 = LDContext.builder("key2").kind(KIND2).anonymous(true).name("name2").build();
        LDContext multiContext = LDContext.createMulti(c1, c2);

        assertSame(multiContext,
                makeDecoratorWithoutPersistence().decorateContext(multiContext, logging.logger));
        logging.assertNothingLogged();
    }

    @Test
    public void multiKindContextGetsGeneratedKeyForIndividualContext() {
        LDContext c1 = LDContext.builder("key1").kind(KIND1).name("name1").build();
        LDContext c2 = LDContext.builder("key2").kind(KIND2).anonymous(true).name("name2").build();
        LDContext multiContext = LDContext.createMulti(c1, c2);
        LDContext transformedMulti = makeDecoratorWithoutPersistence(true)
                .decorateContext(multiContext, logging.logger);

        assertEquals(multiContext.getIndividualContextCount(), transformedMulti.getIndividualContextCount());
        assertSame(multiContext.getIndividualContext(0), transformedMulti.getIndividualContext(0));
        assertContextHasBeenTransformedWithNewKey(
                multiContext.getIndividualContext(1), transformedMulti.getIndividualContext(1));
    }

    @Test
    public void multiKindContextGetsSeparateGeneratedKeyForEachKind() {
        LDContext c1 = LDContext.builder("key1").kind(KIND1).anonymous(true).name("name1").build();
        LDContext c2 = LDContext.builder("key2").kind(KIND2).anonymous(true).name("name2").build();
        LDContext multiContext = LDContext.createMulti(c1, c2);
        LDContext transformedMulti = makeDecoratorWithoutPersistence(true)
                .decorateContext(multiContext, logging.logger);

        assertEquals(multiContext.getIndividualContextCount(), transformedMulti.getIndividualContextCount());
        assertContextHasBeenTransformedWithNewKey(
                multiContext.getIndividualContext(0), transformedMulti.getIndividualContext(0));
        assertContextHasBeenTransformedWithNewKey(
                multiContext.getIndividualContext(1), transformedMulti.getIndividualContext(1));
        assertNotEquals(transformedMulti.getIndividualContext(0).getKey(),
                transformedMulti.getIndividualContext(1).getKey());
    }

    @Test
    public void generatedKeysPersistPerKindIfPersistentStorageIsEnabled() {
        LDContext c1 = LDContext.builder("key1").kind(KIND1).anonymous(true).name("name1").build();
        LDContext c2 = LDContext.builder("key2").kind(KIND2).anonymous(true).name("name2").build();
        LDContext multiContext = LDContext.createMulti(c1, c2);

        PersistentDataStore store = new InMemoryPersistentDataStore();

        ContextDecorator decorator1 = makeDecoratorWithPersistence(store, true);
        LDContext transformedMultiA = decorator1.decorateContext(multiContext, logging.logger);
        assertContextHasBeenTransformedWithNewKey(
                multiContext.getIndividualContext(0), transformedMultiA.getIndividualContext(0));
        assertContextHasBeenTransformedWithNewKey(
                multiContext.getIndividualContext(1), transformedMultiA.getIndividualContext(1));

        ContextDecorator decorator2 = makeDecoratorWithPersistence(store, true);
        LDContext transformedMultiB = decorator2.decorateContext(multiContext, logging.logger);
        assertEquals(transformedMultiA, transformedMultiB);
    }

    @Test
    public void generatedKeysAreReusedDuringLifetimeOfSdkEvenIfPersistentStorageIsDisabled() {
        LDContext c1 = LDContext.builder("key1").kind(KIND1).anonymous(true).name("name1").build();
        LDContext c2 = LDContext.builder("key2").kind(KIND2).anonymous(true).name("name2").build();
        LDContext multiContext = LDContext.createMulti(c1, c2);

        ContextDecorator decorator = makeDecoratorWithoutPersistence(true);
        LDContext transformedMultiA = decorator.decorateContext(multiContext, logging.logger);
        assertContextHasBeenTransformedWithNewKey(
                multiContext.getIndividualContext(0), transformedMultiA.getIndividualContext(0));
        assertContextHasBeenTransformedWithNewKey(
                multiContext.getIndividualContext(1), transformedMultiA.getIndividualContext(1));

        LDContext transformedMultiB = decorator.decorateContext(multiContext, logging.logger);
        assertEquals(transformedMultiA, transformedMultiB);
    }

    @Test
    public void generatedKeysAreNotReusedAcrossRestartsIfPersistentStorageIsDisabled() {
        LDContext c1 = LDContext.builder("key1").kind(KIND1).anonymous(true).name("name1").build();
        LDContext c2 = LDContext.builder("key2").kind(KIND2).anonymous(true).name("name2").build();
        LDContext multiContext = LDContext.createMulti(c1, c2);

        ContextDecorator decorator1 = makeDecoratorWithoutPersistence(true);
        LDContext transformedMultiA = decorator1.decorateContext(multiContext, logging.logger);
        assertContextHasBeenTransformedWithNewKey(
                multiContext.getIndividualContext(0), transformedMultiA.getIndividualContext(0));
        assertContextHasBeenTransformedWithNewKey(
                multiContext.getIndividualContext(1), transformedMultiA.getIndividualContext(1));

        ContextDecorator decorator2 = makeDecoratorWithoutPersistence(true);
        LDContext transformedMultiB = decorator2.decorateContext(multiContext, logging.logger);
        assertContextHasBeenTransformedWithNewKey(
                multiContext.getIndividualContext(0), transformedMultiB.getIndividualContext(0));
        assertNotEquals(transformedMultiA.getIndividualContext(0).getKey(),
                transformedMultiB.getIndividualContext(0).getKey());
        assertContextHasBeenTransformedWithNewKey(
                multiContext.getIndividualContext(1), transformedMultiB.getIndividualContext(1));
        assertNotEquals(transformedMultiA.getIndividualContext(1).getKey(),
                transformedMultiB.getIndividualContext(1).getKey());
    }
    private ContextDecorator makeDecoratorWithPersistence(PersistentDataStore store,
                                                          boolean generateAnonymousKeys) {
        return new ContextDecorator(store, generateAnonymousKeys);
    }

    private ContextDecorator makeDecoratorWithoutPersistence(boolean generateAnonymousKeys) {
        return makeDecoratorWithPersistence(new NullPersistentDataStore(), generateAnonymousKeys);
    }

    private ContextDecorator makeDecoratorWithoutPersistence() {
        return makeDecoratorWithoutPersistence(false);
    }

    private void assertContextHasBeenTransformedWithNewKey(LDContext original, LDContext transformed) {
        assertNotEquals(original.getKey(), transformed.getKey());
        assertEquals(LDContext.builderFromContext(original).key(transformed.getKey()).build(),
            transformed);
    }
}
