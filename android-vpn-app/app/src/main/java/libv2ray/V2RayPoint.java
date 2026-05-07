package libv2ray;

import go.Seq;

public final class V2RayPoint implements Seq.GoObject {
    private final int refnum;

    V2RayPoint(int refnum) {
        this.refnum = refnum;
        Seq.trackGoRef(refnum, this);
    }

    public V2RayPoint() {
        this.refnum = __NewV2RayPoint();
        Seq.trackGoRef(refnum, this);
    }

    @Override
    public int incRefnum() {
        Seq.incGoRef(refnum, this);
        return refnum;
    }

    private static native int __NewV2RayPoint();

    public native V2RayVPNServiceSupportsSet getSupportSet();
    public native void setSupportSet(V2RayVPNServiceSupportsSet supportSet);
    public native boolean getIsRunning();
    public native void setIsRunning(boolean running);
    public native String getDomainName();
    public native void setDomainName(String domainName);
    public native String getConfigureFileContent();
    public native void setConfigureFileContent(String configureFileContent);
    public native boolean getAsyncResolve();
    public native void setAsyncResolve(boolean asyncResolve);
    public native long measureDelay(String url) throws Exception;
    public native long queryStats(String tag, String direction);
    public native void runLoop(boolean preferIPv6) throws Exception;
    public native void stopLoop() throws Exception;
}
