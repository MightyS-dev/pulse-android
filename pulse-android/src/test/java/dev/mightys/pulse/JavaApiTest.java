package dev.mightys.pulse;

import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class JavaApiTest {
    @Test public void oneLineCallsCompileAndNeverThrowBeforeInitialize() {
        Pulse.initialize(null, "APP_KEY");
        Pulse.initialize(null, "APP_KEY", new PulseOptions());
        Pulse.track("level.complete");
        Pulse.track("purchase", 4.99, Collections.singletonMap("sku", "gems_100"));
        Pulse.setUser("stable-user-id");
        Pulse.setUser(null);
        Pulse.flush();
        Pulse.flush(() -> {});
        assertFalse(Pulse.flush(0L));
        assertTrue(Pulse.getOptOut());
    }
}
