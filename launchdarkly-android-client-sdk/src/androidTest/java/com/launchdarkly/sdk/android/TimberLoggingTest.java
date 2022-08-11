package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

@RunWith(AndroidJUnit4.class)
public class TimberLoggingTest {

    @Rule
    public TimberLoggingRule timberLoggingRule = new TimberLoggingRule();

    private TestTree testTree;

    @Before
    public void setUp() throws Exception {
        testTree = new TestTree();
        Timber.plant(testTree);
    }

    @After
    public void tearDown() throws Exception {
       testTree = null;
    }

    @Test
    public void timberTagIsLaunchDarklySdkForAllEvents() {
        LDConfig.log().d("event");
        LDConfig.log().d("event");

        assertEquals(List.of("LaunchDarklySdk", "LaunchDarklySdk"), testTree.loggedTags);
    }

    private static class TestTree extends Timber.Tree {

        final List<String> loggedTags = new ArrayList<String>();

        @Override
        protected void log(int priority, @Nullable String tag, @NonNull String message, @Nullable Throwable t) {
            loggedTags.add(tag);
        }
    }
}
