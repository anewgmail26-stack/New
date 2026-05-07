package libv2ray;

public interface V2RayVPNServiceSupportsSet {
    long setup(String conf);
    long prepare();
    long shutdown();
    boolean protect(long socket);
    long onEmitStatus(long code, String message);
}
