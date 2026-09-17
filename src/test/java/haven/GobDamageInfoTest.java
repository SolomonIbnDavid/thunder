package haven;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GobDamageInfo.record(id) is what the constructor stores into its final
 * damage field. It must never be null, even while "Clear damage" is
 * removing the same id from another thread -- the remote-user NPE in
 * render() (2026-09-12) came from a containsKey/get pair losing that race.
 */
public class GobDamageInfoTest {
    @Test
    void recordAfterForgetStartsEmpty() {
	long id = 424242L;
	GobDamageInfo.DamageVO a = GobDamageInfo.record(id);
	a.shp = 7;
	assertSame(a, GobDamageInfo.record(id), "same record while it exists");
	GobDamageInfo.forget(id);
	GobDamageInfo.DamageVO b = GobDamageInfo.record(id);
	assertNotNull(b);
	assertNotSame(a, b, "forget drops the old record");
	assertTrue(b.isEmpty(), "a fresh record renders nothing");
	GobDamageInfo.forget(id);
    }

    @Test
    void recordNeverNullUnderConcurrentClear() throws Exception {
	final long id = 515151L;
	final int rounds = 300_000;
	AtomicBoolean stop = new AtomicBoolean(false);
	AtomicInteger nulls = new AtomicInteger();
	Thread clearer = new Thread(() -> {
		while(!stop.get()) GobDamageInfo.forget(id);
	    }, "clearer");
	clearer.setDaemon(true);
	clearer.start();
	try {
	    for(int i = 0; i < rounds; i++) {
		if(GobDamageInfo.record(id) == null) nulls.incrementAndGet();
	    }
	} finally {
	    stop.set(true);
	    clearer.join(5000);
	    GobDamageInfo.forget(id);
	}
	assertEquals(0, nulls.get(), "record(id) returned null while another thread was clearing it");
    }
}
