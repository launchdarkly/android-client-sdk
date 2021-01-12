package com.launchdarkly.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.assertNull;

@RunWith(AndroidJUnit4.class)
public class DiagnosticSdkTest {

    @Rule
    public TimberLoggingRule timberLoggingRule = new TimberLoggingRule();

    @Test
    public void defaultFieldValues() {
        DiagnosticSdk diagnosticSdk = new DiagnosticSdk(new LDConfig.Builder().build());
        assertEquals("android-client-sdk", diagnosticSdk.name);
        assertNotNull(diagnosticSdk.version);
        assertNull(diagnosticSdk.wrapperName);
        assertNull(diagnosticSdk.wrapperVersion);
    }

    @Test
    public void getsWrapperValuesFromConfig() {
        LDConfig config = new LDConfig.Builder()
                .setWrapperName("Scala")
                .setWrapperVersion("0.1.0")
                .build();
        DiagnosticSdk diagnosticSdk = new DiagnosticSdk(config);
        assertEquals("android-client-sdk", diagnosticSdk.name);
        assertNotNull(diagnosticSdk.version);
        assertEquals(diagnosticSdk.wrapperName, "Scala");
        assertEquals(diagnosticSdk.wrapperVersion, "0.1.0");
    }

    @Test
    public void gsonSerializationNoWrapper() {
        DiagnosticSdk diagnosticSdk = new DiagnosticSdk(new LDConfig.Builder().build());
        Gson gson = GsonCache.getGson();
        JsonObject jsonObject = gson.toJsonTree(diagnosticSdk).getAsJsonObject();
        assertEquals(2, jsonObject.size());
        assertEquals("android-client-sdk", jsonObject.getAsJsonPrimitive("name").getAsString());
        assertNotNull(jsonObject.getAsJsonPrimitive("version").getAsString());
    }

    @Test
    public void gsonSerializationWithWrapper() {
        LDConfig config = new LDConfig.Builder()
                .setWrapperName("Scala")
                .setWrapperVersion("0.1.0")
                .build();
        DiagnosticSdk diagnosticSdk = new DiagnosticSdk(config);
        Gson gson = GsonCache.getGson();
        JsonObject jsonObject = gson.toJsonTree(diagnosticSdk).getAsJsonObject();
        assertEquals(4, jsonObject.size());
        assertEquals("android-client-sdk", jsonObject.getAsJsonPrimitive("name").getAsString());
        assertNotNull(jsonObject.getAsJsonPrimitive("version").getAsString());
        assertEquals("Scala", jsonObject.getAsJsonPrimitive("wrapperName").getAsString());
        assertEquals("0.1.0", jsonObject.getAsJsonPrimitive("wrapperVersion").getAsString());
    }
}
