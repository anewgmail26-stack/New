package go;

import android.content.Context;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class Seq {
    private static boolean loaded;
    private static final Set<GoObject> tracked = Collections.synchronizedSet(new HashSet<GoObject>());

    private Seq() {}

    public static synchronized void touch() {
        if (!loaded) {
            System.loadLibrary("gojni");
            loaded = true;
        }
    }

    public static void trackGoRef(int refnum, GoObject obj) {
        if (obj != null) tracked.add(obj);
    }

    public static native void setContext(Context context);
    public static native void incGoRef(int refnum, GoObject obj);
    public static native void destroyRef(int refnum);

    public interface GoObject {
        int incRefnum();
    }

    public interface Proxy extends GoObject {}

    public static final class Ref implements GoObject {
        public final int refnum;

        public Ref(int refnum) {
            this.refnum = refnum;
            trackGoRef(refnum, this);
        }

        @Override
        public int incRefnum() {
            incGoRef(refnum, this);
            return refnum;
        }
    }
}
