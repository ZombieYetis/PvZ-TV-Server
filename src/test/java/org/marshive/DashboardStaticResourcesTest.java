package org.marshive;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.*;

public class DashboardStaticResourcesTest {
    private HttpServer server;
    private String base;

    @Before
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (String[] route : new String[][]{{"/almanac/", "AlmanacHandler"}, {"/site/", "SiteAssetHandler"},
                {"/assets/site/ui/", "SiteImageHandler"}, {"/assets/cards/", "CardImageHandler"}}) {
            var constructor = Class.forName("org.marshive.DashboardServer$" + route[1]).getDeclaredConstructor();
            constructor.setAccessible(true);
            server.createContext(route[0], (HttpHandler) constructor.newInstance());
        }
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stop() {
        server.stop(0);
    }

    private HttpURLConnection request(String path, String encoding, String condition) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        if (encoding != null) connection.setRequestProperty("Accept-Encoding", encoding);
        if (condition != null) connection.setRequestProperty("If-None-Match", condition);
        return connection;
    }

    @Test
    public void almanacCompressionPreservesContentAndRepresentationValidators() throws Exception {
        HttpURLConnection plain = request("/almanac/", "gzip;q=0, identity", null);
        assertEquals(200, plain.getResponseCode());
        assertNull(plain.getHeaderField("Content-Encoding"));
        byte[] content = plain.getInputStream().readAllBytes();
        String plainTag = plain.getHeaderField("ETag");
        assertNotNull(plainTag);
        plain.disconnect();

        HttpURLConnection compressed = request("/almanac/", "br, gzip;q=0.5", null);
        assertEquals(200, compressed.getResponseCode());
        assertEquals("gzip", compressed.getHeaderField("Content-Encoding"));
        assertEquals("Accept-Encoding", compressed.getHeaderField("Vary"));
        assertEquals("public, max-age=0, must-revalidate", compressed.getHeaderField("Cache-Control"));
        byte[] zipped = compressed.getInputStream().readAllBytes();
        assertTrue(zipped.length < content.length);
        assertArrayEquals(content, new GZIPInputStream(new ByteArrayInputStream(zipped)).readAllBytes());
        assertNotEquals(plainTag, compressed.getHeaderField("ETag"));
        compressed.disconnect();
        assertTrue(new String(content, StandardCharsets.UTF_8).contains("单次脑光产量"));
    }

    @Test
    public void conditionalRequestsReturnNoBodyAndRejectStaleValidators() throws Exception {
        HttpURLConnection first = request("/site/common.css", "gzip", null);
        assertEquals(200, first.getResponseCode());
        String tag = first.getHeaderField("ETag");
        first.getInputStream().readAllBytes();
        first.disconnect();
        HttpURLConnection unchanged = request("/site/common.css", "gzip", "\"old\", W/" + tag);
        assertEquals(304, unchanged.getResponseCode());
        assertEquals(0, unchanged.getInputStream().readAllBytes().length);
        unchanged.disconnect();
        HttpURLConnection changed = request("/site/common.css", "gzip", "\"old\"");
        assertEquals(200, changed.getResponseCode());
        assertTrue(changed.getInputStream().readAllBytes().length > 0);
        changed.disconnect();
    }

    @Test
    public void originalAssetsAreServedWithCorrectTypesAndUnknownPathsAreRejected() throws Exception {
        for (String path : new String[]{"/site/ui/almanac-book.webp", "/assets/site/ui/home-background.webp",
                "/assets/cards/SEED_PACKET_00.webp"}) {
            HttpURLConnection image = request(path, "gzip", null);
            assertEquals(path, 200, image.getResponseCode());
            assertEquals("image/webp", image.getHeaderField("Content-Type"));
            assertNull(image.getHeaderField("Content-Encoding"));
            assertTrue(image.getInputStream().readAllBytes().length > 0);
            image.disconnect();
        }
        for (String path : new String[]{"/site/ui/missing.webp", "/assets/cards/bogus.webp", "/almanac/missing"}) {
            HttpURLConnection missing = request(path, null, null);
            assertEquals(404, missing.getResponseCode());
            missing.disconnect();
        }
    }

    @Test
    public void versionedCardImagesUseImmutableCaching() throws Exception {
        String path = "/assets/cards/SEED_PACKET_00.webp?v=pvz-release-20261106-6";
        HttpURLConnection image = request(path, null, null);
        assertEquals(200, image.getResponseCode());
        assertEquals("image/webp", image.getHeaderField("Content-Type"));
        assertEquals("public, max-age=31536000, immutable", image.getHeaderField("Cache-Control"));
        String etag = image.getHeaderField("ETag");
        assertTrue(image.getInputStream().readAllBytes().length > 0);
        image.disconnect();

        HttpURLConnection unchanged = request(path, null, etag);
        assertEquals(304, unchanged.getResponseCode());
        assertEquals("public, max-age=31536000, immutable", unchanged.getHeaderField("Cache-Control"));
        unchanged.disconnect();

        HttpURLConnection unversioned = request("/assets/cards/SEED_PACKET_00.webp", null, null);
        assertEquals(200, unversioned.getResponseCode());
        assertEquals("public, max-age=0, must-revalidate", unversioned.getHeaderField("Cache-Control"));
        unversioned.disconnect();
    }
}
