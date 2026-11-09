package ac.anycast.mc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.anycast.mc.core.UpdateCheck.Info;
import ac.anycast.mc.core.UpdateCheck.Level;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UpdateCheckTest {
    static final UpdateCheck.ReleaseCheck ALL = v -> true;
    static final UpdateCheck.ReleaseCheck NONE = v -> false;

    @Test
    void compareVersions() {
        assertTrue(UpdateCheck.compare("1.10.0", "1.9.3") > 0);
        assertEquals(0, UpdateCheck.compare("1.2", "1.2.0"));
        assertTrue(UpdateCheck.compare("1.1.1", "1.2.0") < 0);
        assertTrue(UpdateCheck.compare("2.0", "1.99.99") > 0);
    }

    @Test
    void parseFile() {
        Info i = UpdateCheck.parse("# AnyCast.ac Minecraft Addon\nlatest 1.2.0\nrequired 1.1.1\n");
        assertEquals("1.2.0", i.latest());
        assertEquals("1.1.1", i.required());
        assertNull(UpdateCheck.parse("<html><title>Checking your browser</title></html>"), "a captcha page is not a version file");
        assertNull(UpdateCheck.parse("required 1.2.0\n"), "no latest, nothing to say");
        assertNull(UpdateCheck.parse("latest 1.2.0; rm -rf\n"));
        assertNull(UpdateCheck.parse("latest 99999999.1\n"));
        Info broken = UpdateCheck.parse("latest 1.2.0\nrequired 9.0.0\n");
        assertNull(broken.required(), "required above latest is a broken file and must not stop servers");
    }

    @Test
    void levels() {
        Info i = new Info("1.3.0", "1.2.0");
        assertEquals(Level.OK, UpdateCheck.evaluate(i, "1.3.0", ALL).level());
        assertEquals(Level.OK, UpdateCheck.evaluate(i, "1.4.0", ALL).level(), "newer than published (a test build)");
        assertEquals(Level.OUTDATED, UpdateCheck.evaluate(i, "1.2.5", ALL).level());
        assertEquals(Level.CRITICAL, UpdateCheck.evaluate(i, "1.1.9", ALL).level());
        assertEquals(Level.OUTDATED, UpdateCheck.evaluate(i, "1.1.9", NONE).level(),
                "without the release on GitHub the server is only reminded, never stopped");
        assertEquals(Level.UNKNOWN, UpdateCheck.evaluate(null, "1.1.9", ALL).level());
        List<String> asked = new ArrayList<>();
        UpdateCheck.evaluate(i, "1.1.9", v -> asked.add(v));
        assertEquals(List.of("1.2.0"), asked, "the required version is the one that must exist");
    }

    @Test
    void banners() {
        String crit = String.join("\n", UpdateCheck.banner(UpdateCheck.evaluate(new Info("1.3.0", "1.2.0"), "1.1.0", ALL)));
        assertTrue(crit.contains("не запустится") && crit.contains("1.2.0") && crit.contains(UpdateCheck.RELEASES), crit);
        String old = String.join("\n", UpdateCheck.banner(UpdateCheck.evaluate(new Info("1.3.0", null), "1.2.0", ALL)));
        assertTrue(old.contains("1.3.0") && !old.contains("не запустится"), old);
        assertTrue(UpdateCheck.banner(UpdateCheck.evaluate(new Info("1.3.0", null), "1.3.0", ALL)).isEmpty());
        assertFalse(String.join("", UpdateCheck.banner(UpdateCheck.evaluate(new Info("1.3.0", null), "1.2.0", ALL))).contains("—"));
    }

    @Test
    void devBuildIsNotChecked() {
        assertFalse(UpdateCheck.isRelease("dev"));
        assertEquals(Level.UNKNOWN, new UpdateCheck(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))).check("dev", "test").level());
    }
}
