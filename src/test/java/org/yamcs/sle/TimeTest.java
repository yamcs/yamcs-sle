package org.yamcs.sle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yamcs.jsle.CcsdsTime;
import org.yamcs.time.Instant;
import org.yamcs.utils.TimeEncoding;

import com.google.protobuf.Timestamp;

public class TimeTest {

    @BeforeAll
    public static void beforeClass() {
        TimeEncoding.setUp();
    }

    @Test
    public void test1() {
        Instant inst1 = TimeEncoding.parseHres("2021-01-01T00:01:02.123456891");
        Timestamp ts = TimeEncoding.toProtobufTimestamp(inst1);

        CcsdsTime ccsdsTime = CcsdsTime.fromUnix(ts.getSeconds(), ts.getNanos());
        Instant inst2 = AbstractTmSleLink.toInstant(ccsdsTime);

        assertEquals(inst1, inst2);
    }

    @Test
    public void test2() {

        Instant inst1 = TimeEncoding.parseHres("2021-01-01T00:01:02.123456891");
        Timestamp ts = TimeEncoding.toProtobufTimestamp(inst1);

        CcsdsTime ccsdsTime = CcsdsTime.fromUnix(ts.getSeconds(), ts.getNanos());
        Instant inst2 = AbstractTmSleLink.toInstant(ccsdsTime);

        assertEquals(inst1, inst2);

    }
}
