package libv2ray;

import go.Seq;

public final class ProtectedDialer implements Seq.GoObject {
    private final int refnum;

    ProtectedDialer(int refnum) {
        this.refnum = refnum;
        Seq.trackGoRef(refnum, this);
    }

    public ProtectedDialer() {
        this.refnum = __New();
        Seq.trackGoRef(refnum, this);
    }

    @Override
    public int incRefnum() {
        Seq.incGoRef(refnum, this);
        return refnum;
    }

    private static native int __New();
    public native boolean isVServerReady();
    public native void prepareResolveChan();
    public native boolean protect(String address);
}
