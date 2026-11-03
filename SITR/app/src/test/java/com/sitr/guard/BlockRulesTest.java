package com.sitr.guard;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class BlockRulesTest {
    private byte[] query(String domain) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x12,0x34,0x01,0x00,0x00,0x01,0,0,0,0,0,0});
        for (String label : domain.split("\\.")) {
            byte[] b = label.getBytes(StandardCharsets.US_ASCII);
            out.write(b.length);
            out.write(b);
        }
        out.write(new byte[]{0,0,1,0,1});
        return out.toByteArray();
    }

    @Test public void detectsDomainInQuery() throws Exception {
        assertEquals("ads.doubleclick.net", BlockRules.queryHost(query("ads.doubleclick.net")));
        assertEquals("www.youtube.com", BlockRules.queryHost(query("www.youtube.com")));
    }

    @Test public void generatesNxdomain() throws Exception {
        byte[] q = query("tracker.example.net");
        byte[] response = BlockRules.blockedDnsReply(q);
        assertEquals(0x80, response[2] & 0x80);
        assertEquals(3, response[3] & 0x0F);
        assertEquals(0, response[7]);
        assertEquals(q.length, response.length);
    }

    @Test public void rejectsMalformedQuery() {
        assertNull(BlockRules.queryHost(new byte[]{1,2,3}));
    }
}
