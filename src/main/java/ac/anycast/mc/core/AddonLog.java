package ac.anycast.mc.core;

/** Platform logger. */
public interface AddonLog {
    void info(String msg);

    void warn(String msg);

    static AddonLog of(java.util.logging.Logger l) {
        return new AddonLog() {
            @Override
            public void info(String msg) {
                l.info(msg);
            }

            @Override
            public void warn(String msg) {
                l.warning(msg);
            }
        };
    }
}
