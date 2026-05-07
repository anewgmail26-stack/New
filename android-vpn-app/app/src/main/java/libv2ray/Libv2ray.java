package libv2ray;

import go.Seq;
import go.Universe;

public abstract class Libv2ray {
    static {
        Seq.touch();
        Universe.touch();
        _init();
    }

    private Libv2ray() {}

    public static void touch() {}

    private static native void _init();

    public static native String checkVersionX();
    public static native void initV2Env(String envPath);
    public static native long measureOutboundDelay(String configureFileContent, String url) throws Exception;
    public static native V2RayPoint newV2RayPoint(V2RayVPNServiceSupportsSet supportSet, boolean asyncDns);
    public static native void testConfig(String configureFileContent) throws Exception;

    private static final class proxyV2RayVPNServiceSupportsSet implements Seq.Proxy, V2RayVPNServiceSupportsSet {
        private final int refnum;

        proxyV2RayVPNServiceSupportsSet(int refnum) {
            this.refnum = refnum;
            Seq.trackGoRef(refnum, this);
        }

        @Override
        public int incRefnum() {
            Seq.incGoRef(refnum, this);
            return refnum;
        }

        @Override
        public native long setup(String conf);

        @Override
        public native long prepare();

        @Override
        public native long shutdown();

        @Override
        public native boolean protect(long socket);

        @Override
        public native long onEmitStatus(long code, String message);
    }
}
