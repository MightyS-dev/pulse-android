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
        Pulse.trackError(new IllegalStateException("handled"));
        Pulse.trackError(new IllegalStateException("handled"), Collections.singletonMap("screen", "cart"));
        Pulse.setUser("stable-user-id");
        Pulse.setUser(null);
        Pulse.flush();
        Pulse.flush(() -> {});
        assertFalse(Pulse.flush(0L));
        assertTrue(Pulse.getOptOut());
    }

    @Test public void optionsStaySourceCompatibleForJava() {
        PulseOptions plain = new PulseOptions("https://in.mightys.dev");
        assertTrue(plain.getAutomaticScreenViews()); assertTrue(plain.getAutomaticSignals());
        PulseOptions off = new PulseOptions("https://in.mightys.dev", 20.0, 100, 1000, 7.0, true, null, false, null, false, false);
        assertFalse(off.getAutomaticScreenViews()); assertFalse(off.getAutomaticSignals());
        assertTrue(plain.getCaptureErrors()); assertEquals(ErrorDetail.FULL, plain.getErrorDetail());
    }

    @Test public void appControlIsReachableFromJava() {
        PulseControl control = Pulse.getControl();
        assertEquals(ControlState.Kind.Normal, control.getState().getKind());
        ControlListener listener = state -> {};
        control.addListener(listener);
        control.removeListener(listener);
        control.checkNow();
        control.dismiss();
        assertEquals(ControlMode.Automatic, new PulseOptions().getControl());
        assertEquals(ControlMode.Off, new PulseOptions().copy(null, 20.0, 100, 1000, 7.0, true, null, false, null, true, true, true, ErrorDetail.FULL, true, ControlMode.Off).getControl());
    }
}
