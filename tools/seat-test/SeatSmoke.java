import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import android.content.Context;
import android.content.SharedPreferences;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class SeatSmoke {
    @Test
    public void bootContextAndSharedPrefs() {
        Context ctx = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = ctx.getSharedPreferences("seat", Context.MODE_PRIVATE);
        prefs.edit().putString("k", "v").commit();
        assertEquals("v", prefs.getString("k", null));
        System.out.println("SEAT-SMOKE: context=" + ctx.getPackageName() + " prefs-ok");
    }
    @Test
    public void loopbackHttpSocket() throws Exception {
        OkHttpClient client = new OkHttpClient();
        Response resp = client.newCall(new Request.Builder().url("http://127.0.0.1:18099/hello").build()).execute();
        assertEquals("world", resp.body().string());
        System.out.println("SEAT-SMOKE: loopback-http-ok");
    }
}
