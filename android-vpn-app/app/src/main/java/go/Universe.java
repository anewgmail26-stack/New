package go;

public abstract class Universe {
    static {
        Seq.touch();
        _init();
    }

    private Universe() {}

    public static void touch() {}

    private static native void _init();

    private static final class proxyerror extends Exception implements Seq.Proxy, error {
        private final int refnum;

        proxyerror(int refnum) {
            this.refnum = refnum;
            Seq.trackGoRef(refnum, this);
        }

        @Override
        public int incRefnum() {
            Seq.incGoRef(refnum, this);
            return refnum;
        }

        @Override
        public String getMessage() {
            return error();
        }

        @Override
        public native String error();
    }
}
