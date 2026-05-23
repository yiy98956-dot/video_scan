package com.videoplatform.cpp.controller;

import com.videoplatform.video.client.CppVideoClient;
import com.videoplatform.video.service.VideoCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * CppProxyController 单元测试
 */
@ExtendWith(MockitoExtension.class)
class CppProxyControllerTest {

    @Mock
    private CppVideoClient cppClient;
    @Mock
    private VideoCacheService videoCacheService;

    private CppProxyController controller;

    @BeforeEach
    void setUp() {
        controller = new CppProxyController(cppClient, videoCacheService);
    }

    @Test
    @DisplayName("C++ 返回 ERROR 文本时应跳过尝试 1，走直连")
    void proxy_shouldSkipCppErrorAndFallbackToDirect() {
        String videoUrl = "https://cdn.example.com/video.m3u8";

        // ── 尝试 1: C++ proxy 返回 "ERROR: fetch failed" ──
        when(cppClient.fetchRawBytes(anyString()))
                .thenReturn("ERROR: fetch failed".getBytes(StandardCharsets.UTF_8));

        // ── 尝试 2: 直连成功 ──
        String validM3u8 = "#EXTM3U\n#EXTINF:-1,Test\nhttps://cdn.example.com/seg1.ts\n";
        when(cppClient.fetchDirectBytes(videoUrl))
                .thenReturn(validM3u8.getBytes(StandardCharsets.UTF_8));

        ResponseEntity<Object> response = controller.proxy(videoUrl);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        String body = new String((byte[]) response.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("#EXTM3U"), "应包含 m3u8 标记");

        // 验证：C++ proxy 和直连都被调用
        verify(cppClient).fetchRawBytes(anyString());
        verify(cppClient).fetchDirectBytes(videoUrl);
    }

    @Test
    @DisplayName("直连 m3u8 中包含相对路径时应重写为 /api/go/proxy 格式")
    void directFetch_shouldRewriteRelativePathsInM3u8() {
        String videoUrl = "https://cdn.example.com/2025/stream.m3u8";

        // 尝试 1: C++ 失败
        when(cppClient.fetchRawBytes(anyString()))
                .thenReturn("ERROR: fetch failed".getBytes(StandardCharsets.UTF_8));
        // 尝试 2: 直连返回含相对路径的 m3u8
        String rawM3u8 = "#EXTM3U\n#EXTINF:-1,Seg1\nseg1.ts\n#EXTINF:-1,Seg2\n../seg2.ts\n";
        when(cppClient.fetchDirectBytes(videoUrl))
                .thenReturn(rawM3u8.getBytes(StandardCharsets.UTF_8));

        ResponseEntity<Object> response = controller.proxy(videoUrl);

        assertEquals(200, response.getStatusCode().value());
        String body = new String((byte[]) response.getBody(), StandardCharsets.UTF_8);

        // 验证：相对路径被重写为 /api/go/proxy?url= + 绝对URL
        assertTrue(body.contains("/api/go/proxy?url=https%3A%2F%2Fcdn.example.com%2F2025%2Fseg1.ts"));
        assertTrue(body.contains("/api/go/proxy?url=https%3A%2F%2Fcdn.example.com%2Fseg2.ts"));
    }

    @Test
    @DisplayName("m3u8 中相对 URL 应重写为 /api/go/proxy?url= 格式")
    void proxy_shouldRewriteRelativeUrlsToCppProxy() {
        String videoUrl = "https://cdn.example.com/path/playlist.m3u8";

        // CDN 返回的原始 m3u8 包含相对路径
        String rawM3u8 = "#EXTM3U\n#EXTINF:-1,Segment1\nseg1.ts\n";
        when(cppClient.fetchRawBytes(anyString()))
                .thenReturn(rawM3u8.getBytes(StandardCharsets.UTF_8));

        ResponseEntity<Object> response = controller.proxy(videoUrl);

        assertEquals(200, response.getStatusCode().value());
        String body = new String((byte[]) response.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("/api/go/proxy?url=https%3A%2F%2Fcdn.example.com%2Fpath%2Fseg1.ts"));
    }

    @Test
    @DisplayName("非 m3u8 内容应原样返回，路径不做重写")
    void proxy_shouldPassthroughNonM3u8Content() {
        String videoUrl = "https://cdn.example.com/video.mp4";
        byte[] mp4Data = "fake-mp4-binary-content".getBytes(StandardCharsets.UTF_8);

        when(cppClient.fetchRawBytes(anyString()))
                .thenReturn(mp4Data);

        ResponseEntity<byte[]> response = controller.proxy(videoUrl);

        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(mp4Data, response.getBody());
    }

    @Test
    @DisplayName("C++ proxy 和直连都失败时应返回 502")
    void proxy_shouldReturn502WhenAllMethodsFail() {
        String videoUrl = "https://cdn.example.com/video.ts";

        // 尝试 1: C++ proxy 返回 "ERROR: fetch failed"
        when(cppClient.fetchRawBytes(anyString()))
                .thenReturn("ERROR: fetch failed".getBytes(StandardCharsets.UTF_8));
        // 尝试 2: 直连返回 null
        when(cppClient.fetchDirectBytes(videoUrl))
                .thenReturn(null);

        ResponseEntity<byte[]> response = controller.proxy(videoUrl);

        assertEquals(502, response.getStatusCode().value());
    }

    @Test
    @DisplayName("C++ proxy 返回空数据时应跳过，走直连")
    void proxy_shouldSkipCppWhenDataIsEmpty() {
        String videoUrl = "https://cdn.example.com/video.m3u8";

        // 尝试 1: 返回空数组
        when(cppClient.fetchRawBytes(anyString()))
                .thenReturn(new byte[0]);
        // 尝试 2: 直连成功
        when(cppClient.fetchDirectBytes(videoUrl))
                .thenReturn("data".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<byte[]> response = controller.proxy(videoUrl);

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    @DisplayName("fetchRawBytes 抛出异常时应返回 502，而非 500")
    void proxy_shouldReturn502WhenClientThrows() {
        String videoUrl = "https://cdn.example.com/video.ts";

        // fetchRawBytes 抛出运行时异常（模拟生产环境网络超时）
        when(cppClient.fetchRawBytes(anyString()))
                .thenThrow(new RuntimeException("Connection timed out"));

        ResponseEntity<byte[]> response = controller.proxy(videoUrl);

        // 验证：不应返回 500，应返回 502
        assertEquals(502, response.getStatusCode().value());
        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("Connection timed out"), "应包含原始异常信息");
    }

    @Test
    @DisplayName("url 含特殊字符时不应崩溃")
    void proxy_shouldHandleSpecialCharsInUrl() {
        String cdnUrl = "https://cdn.example.com/2025/02/27/测试文件/stream.m3u8";

        when(cppClient.fetchRawBytes(anyString()))
                .thenReturn("#EXTM3U\n#EXTINF:-1,Test\nseg1.ts\n".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<byte[]> response = controller.proxy(cdnUrl);

        assertEquals(200, response.getStatusCode().value());
        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("seg1.ts"));
    }

    @Test
    @DisplayName("极大 URL 不应导致 substring 越界")
    void proxy_shouldHandleVeryLongUrl() {
        StringBuilder sb = new StringBuilder("https://cdn.example.com/");
        for (int i = 0; i < 500; i++) sb.append("a");
        sb.append("/index.m3u8");
        String longUrl = sb.toString();

        when(cppClient.fetchRawBytes(anyString()))
                .thenReturn(new byte[0]);
        when(cppClient.fetchDirectBytes(longUrl))
                .thenReturn("ok".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<byte[]> response = controller.proxy(longUrl);

        assertEquals(200, response.getStatusCode().value());
    }
}
