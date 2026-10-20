import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.Components;
import com.launchdarkly.sdk.android.LDClient;
import com.launchdarkly.sdk.android.LDConfig;
import com.launchdarkly.sdk.android.integrations.PollingDataSourceBuilder;

import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class SeatInit {
    @Test
    public void initOnPolling() throws Exception {
        android.app.Application app = (android.app.Application) RuntimeEnvironment.getApplication();
        LDConfig.Builder b = new LDConfig.Builder(LDConfig.Builder.AutoEnvAttributes.Disabled);
        b.mobileKey("mob-real-key-here");
        b.dataSource(Components.pollingDataSource().pollIntervalMillis(50));
        b.serviceEndpoints(Components.serviceEndpoints().polling("http://127.0.0.1:18099").events("http://127.0.0.1:18099"));
        b.diagnosticOptOut(true);
        LDContext ctx = LDContext.builder("u1").build();
        Future<LDClient> f = LDClient.init(app, b.build(), ctx);
        LDClient c = f.get();
        assertTrue(c.isInitialized());
        assertEquals("on", c.stringVariation("a-flag", "off"));
        System.out.println("SEAT-INIT-OK initialized=" + c.isInitialized() + " flag=" + c.stringVariation("a-flag", "off")
            + " version=" + com.launchdarkly.sdk.android.BuildConfig.VERSION_NAME);
        c.close();
    }
}
