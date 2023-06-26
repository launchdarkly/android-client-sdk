package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import org.junit.Rule;
import org.junit.Test;

public class AnonymousKeyContextModifierTest {
    private static final ContextKind KIND1 = ContextKind.of("kind1");
    private static final ContextKind KIND2 = ContextKind.of("kind2");

    @Test
    public void singleKindNonAnonymousContextIsUnchanged() {
        LDContext context = LDContext.builder("key1").name("name").build();
        assertEquals(context,
                makeDecoratorWithoutPersistence().modifyContext(context));
    }

    @Test
    public void singleKindAnonymousContextIsUnchangedIfConfigOptionIsNotSet() {
        LDContext context = LDContext.builder("key1").anonymous(true).name("name").build();
        assertEquals(context,
                makeDecoratorWithoutPersistence().modifyContext(context));
    }

    @Test
    public void singleKindAnonymousContextGetsGeneratedKeyIfConfigOptionIsSet() {
        LDContext context = LDContext.builder("placeholder").anonymous(true).name("name").build();
        LDContext transformed = makeDecoratorWithoutPersistence(true)
                .modifyContext(context);
        assertContextHasBeenTransformedWithNewKey(context, transformed);
    }

    @Test
    public void multiKindContextIsUnchangedIfNoIndividualContextsNeedGeneratedKey() {
        LDContext c1 = LDContext.builder("key1").kind(KIND1).name("name1").build();
        LDContext c2 = LDContext.builder("key2").kind(KIND2).anonymous(true).name("name2").build();
        LDContext multiContext = LDContext.createMulti(c1, c2);

        assertSame(multiContext,
                makeDecoratorWithoutPersistence().modifyContext(multiContext));
    }

    @Test
    public void multiKindContextGetsGeneratedKeyForIndividualContext() {
        LDContext c1 = LDContext.builder("key1").kind(KIND1).name("name1").build();
        LDContext c2 = LDContext.builder("key2").kind(KIND2).anonymous(true).name("name2").build();
        LDContext multiContext = LDContext.createMulti(c1, c2);
        LDContext transformedMulti = makeDecoratorWithoutPersistence(true)
                .modifyContext(multiContext);

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
                .modifyContext(multiContext);

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

        AnonymousKeyContextModifier decorator1 = makeDecoratorWithPersistence(store, true);
        LDContext transformedMultiA = decorator1.modifyContext(multiContext);
        assertContextHasBeenTransformedWithNewKey(
                multiContext.getIndividualContext(0), transformedMultiA.getIndividualContext(0));
        assertContextHasBeenTransformedWithNewKey(
                multiContext.getIndividualContext(1), transformedMultiA.getIndividualContext(1));

        AnonymousKeyContextModifier decorator2 = makeDecoratorWithPersistence(store, true);
        LDContext transformedMultiB = decorator2.modifyContext(multiContext);
        assertEquals(transformedMultiA, transformedMultiB);
    }

    @Test
    public void generatedKeysAreReusedDuringLifetimeOfSdkEvenIfPersistentStorageIsDisabled() {
        LDContext c1 = LDContext.builder("key1").kind(KIND1).anonymous(true).name("name1").build();
        LDContext c2 = LDContext.builder("key2").kind(KIND2).anonymous(true).name("name2").build();
        LDContext multiContext = LDContext.createMulti(c1, c2);

        AnonymousKeyContextModifier decorator = makeDecoratorWithoutPersistence(true);
        LDContext transformedMultiA = decorator.modifyContext(multiContext);
        assertContextHasBeenTransformedWithNewKey(
                multiContext.getIndividualContext(0), transformedMultiA.getIndividualContext(0));
        assertContextHasBeenTransformedWithNewKey(
                multiContext.getIndividualContext(1), transformedMultiA.getIndividualContext(1));

        LDContext transformedMultiB = decorator.modifyContext(multiContext);
        assertEquals(transformedMultiA, transformedMultiB);
    }

    @Test
    public void generatedKeysAreNotReusedAcrossRestartsIfPersistentStorageIsDisabled() {
        LDContext c1 = LDContext.builder("key1").kind(KIND1).anonymous(true).name("name1").build();
        LDContext c2 = LDContext.builder("key2").kind(KIND2).anonymous(true).name("name2").build();
        LDContext multiContext = LDContext.createMulti(c1, c2);

        AnonymousKeyContextModifier decorator1 = makeDecoratorWithoutPersistence(true);
        LDContext transformedMultiA = decorator1.modifyContext(multiContext);
        assertContextHasBeenTransformedWithNewKey(
                multiContext.getIndividualContext(0), transformedMultiA.getIndividualContext(0));
        assertContextHasBeenTransformedWithNewKey(
                multiContext.getIndividualContext(1), transformedMultiA.getIndividualContext(1));

        AnonymousKeyContextModifier decorator2 = makeDecoratorWithoutPersistence(true);
        LDContext transformedMultiB = decorator2.modifyContext(multiContext);
        assertContextHasBeenTransformedWithNewKey(
                multiContext.getIndividualContext(0), transformedMultiB.getIndividualContext(0));
        assertNotEquals(transformedMultiA.getIndividualContext(0).getKey(),
                transformedMultiB.getIndividualContext(0).getKey());
        assertContextHasBeenTransformedWithNewKey(
                multiContext.getIndividualContext(1), transformedMultiB.getIndividualContext(1));
        assertNotEquals(transformedMultiA.getIndividualContext(1).getKey(),
                transformedMultiB.getIndividualContext(1).getKey());
    }
    private AnonymousKeyContextModifier makeDecoratorWithPersistence(PersistentDataStore store,
                                                                     boolean generateAnonymousKeys) {
        PersistentDataStoreWrapper persistentData = new PersistentDataStoreWrapper(store, LDLogger.none());
        return new AnonymousKeyContextModifier(persistentData, generateAnonymousKeys);
    }

    private AnonymousKeyContextModifier makeDecoratorWithoutPersistence(boolean generateAnonymousKeys) {
        return makeDecoratorWithPersistence(new NullPersistentDataStore(), generateAnonymousKeys);
    }

    private AnonymousKeyContextModifier makeDecoratorWithoutPersistence() {
        return makeDecoratorWithoutPersistence(false);
    }

    private void assertContextHasBeenTransformedWithNewKey(LDContext original, LDContext transformed) {
        assertNotEquals(original.getKey(), transformed.getKey());
        assertEquals(LDContext.builderFromContext(original).key(transformed.getKey()).build(),
            transformed);
    }
}
