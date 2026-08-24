package xyz.wagyourtail.jvmdg.j19.stub.java_base;

import xyz.wagyourtail.jvmdg.version.Ref;
import xyz.wagyourtail.jvmdg.version.Stub;

import java.time.Duration;

public class J_L_Thread {

    @Stub
    public static boolean join(Thread thread, Duration duration) throws InterruptedException {
        // Java 19 semantics: wait at most the given duration (<= 0 returns immediately); report whether the thread terminated.
        if (duration.isZero() || duration.isNegative()) {
            return !thread.isAlive();
        }
        long millis = duration.toMillis();
        int nanos = duration.getNano() % 1_000_000;
        if (millis == 0 && nanos == 0) {
            nanos = 1; // sub-microsecond positive duration: avoid join(0, 0), which waits forever
        }
        thread.join(millis, nanos);
        return !thread.isAlive();
    }

    @Stub(ref = @Ref("Ljava/lang/Thread;"))
    public static void sleep(Duration duration) throws InterruptedException {
        long millis = duration.toMillis();
        int nanos = duration.getNano() % 1_000_000;
        Thread.sleep(millis, nanos);
    }

    @Stub
    public static long threadId(Thread thread) {
        return thread.getId();
    }

}
