package com.launchdarkly.sdk.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

@RunWith(AndroidJUnit4.class)
public class ContextHasherTest {

    @Test
    public void testContextHasherReturnsUniqueResults(){
        ContextHasher contextHasher1 = new ContextHasher();

        String input1 = "{'key':'userKey1'}";
        String input2 = "{'key':'userKey2'}";

        assertNotEquals("Expected different results! instead got the same.", contextHasher1.hash(input1), contextHasher1.hash(input2));
    }

    @Test
    public void testDifferentContextHashersReturnSameResults(){
        ContextHasher contextHasher1 = new ContextHasher();
        ContextHasher contextHasher2 = new ContextHasher();
        ContextHasher contextHasher3 = new ContextHasher();

        String input1 = "{'key':'userKey1','email':'fake@example.com'}";
        String output1 = contextHasher1.hash(input1);
        String output2 = contextHasher2.hash(input1);
        String output3 = contextHasher3.hash(input1);

        assertEquals("Expected the same, but got different!", output1, output2);
        assertEquals("Expected the same, but got different!", output1, output3);
        assertEquals("Expected the same, but got different!", output2, output3);
    }
}