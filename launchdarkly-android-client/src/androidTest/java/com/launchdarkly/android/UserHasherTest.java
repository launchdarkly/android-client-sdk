package com.launchdarkly.android;

import android.support.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

@RunWith(AndroidJUnit4.class)
public class UserHasherTest {

    @Rule
    public TimberLoggingRule timberLoggingRule = new TimberLoggingRule();

    @Test
    public void testUserHasherReturnsUniqueResults(){
        UserHasher userHasher1 = new UserHasher();

        String input1 = "{'key':'userKey1'}";
        String input2 = "{'key':'userKey2'}";

        assertNotEquals("Expected different results! instead got the same.", userHasher1.hash(input1), userHasher1.hash(input2));
    }

    @Test
    public void testDifferentUserHashersReturnSameResults(){
        UserHasher userHasher1 = new UserHasher();
        UserHasher userHasher2 = new UserHasher();
        UserHasher userHasher3 = new UserHasher();

        String input1 = "{'key':'userKey1','email':'fake@example.com'}";
        String output1 = userHasher1.hash(input1);
        String output2 = userHasher2.hash(input1);
        String output3 = userHasher3.hash(input1);

        assertEquals("Expected the same, but got different!", output1, output2);
        assertEquals("Expected the same, but got different!", output1, output3);
        assertEquals("Expected the same, but got different!", output2, output3);
    }
}