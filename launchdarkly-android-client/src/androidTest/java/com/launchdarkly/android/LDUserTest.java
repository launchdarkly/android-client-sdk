package com.launchdarkly.android;

import android.support.test.runner.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;

/**
 * Created by Farhan on 2018-01-02.
 */
@RunWith(AndroidJUnit4.class)
public class LDUserTest {

    @Test
    public void testPrivateAttributesAreAddedToTheList() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .privateAvatar("privateAvatar")
                .privateCustom("privateValue1", "123")
                .email("email@server.net");

        LDUser ldUser = builder.build();

        Assert.assertNotNull(ldUser);
        Assert.assertEquals(ldUser.getPrivateAttributeNames().size(), 2);
        Assert.assertTrue(ldUser.getPrivateAttributeNames().contains(LDUser.AVATAR));
        Assert.assertFalse(ldUser.getPrivateAttributeNames().contains(LDUser.EMAIL));
        Assert.assertTrue(ldUser.getPrivateAttributeNames().contains("privateValue1"));
    }


    @Test
    public void testBuilderCustomWhenPrivateAttributesProvided() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .custom("k1", "v1")
                .privateCustom("k2", "v2")
                .custom("k3", "v3");

        Set<String> privateAttributeNames = builder.getPrivateAttributeNames();

        Assert.assertNotNull(privateAttributeNames);
        Assert.assertFalse(privateAttributeNames.contains("k1"));
        Assert.assertTrue(privateAttributeNames.contains("k2"));
        Assert.assertFalse(privateAttributeNames.contains("k3"));
    }

    @Test
    public void testBuilderCustomWhenPrivateAttributesNotProvided() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .custom("k1", "v1")
                .custom("k2", "v2");

        Set<String> privateAttributeNames = builder.getPrivateAttributeNames();

        Assert.assertNotNull(privateAttributeNames);
        Assert.assertEquals(privateAttributeNames.size(), 0);
    }

}
