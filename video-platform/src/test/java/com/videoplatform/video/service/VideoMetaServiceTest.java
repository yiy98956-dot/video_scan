package com.videoplatform.video.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoplatform.video.client.CppVideoClient;
import com.videoplatform.video.dto.CppMovieDTO;
import com.videoplatform.video.dto.VideoDetailVO;
import com.videoplatform.video.mapper.VideoMetaMapper;
import com.videoplatform.video.mapper.CategoryVisibilityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;

import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * VideoMetaService 单元测试
 * <p>
 * 验证核心行为：
 * 1. C++ 返回带 plays 的详情时，VO 应包含 plays
 * 2. C++ 返回 null 时，getVideoDetail 应返回 null
 * 3. C++ 返回无 plays 的详情时，VO 的 plays 为 null
 */
@ExtendWith(MockitoExtension.class)
class VideoMetaServiceTest {

    @Mock
    private CppVideoClient cppClient;
    @Mock
    private VideoMetaMapper videoMetaMapper;

    @Mock
    private CategoryVisibilityMapper visibilityMapper;
    @Mock
    private CacheManager cacheManager;
    @Mock
    private Executor videoExecutor;

    private VideoMetaService service;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        service = new VideoMetaService(cppClient, videoMetaMapper, visibilityMapper, mapper, cacheManager, videoExecutor);
    }

    @Test
    @DisplayName("C++ 返回带 plays 的详情时，VO 应包含 plays")
    void getVideoDetail_shouldIncludePlaysWhenCppReturnsThem() {
        // ── 准备 C++ 返回数据（含 plays） ──
        CppMovieDTO.CppPlayUrl url1 = new CppMovieDTO.CppPlayUrl("第01集", "https://cdn.example.com/ep1.m3u8");
        CppMovieDTO.CppPlayUrl url2 = new CppMovieDTO.CppPlayUrl("第02集", "https://cdn.example.com/ep2.m3u8");
        CppMovieDTO.CppPlayGroup group = new CppMovieDTO.CppPlayGroup("bdzy", "线路1", List.of(url1, url2));
        
        CppMovieDTO movie = new CppMovieDTO();
        movie.setId(1001);
        movie.setTitle("测试电影");
        movie.setCoverUrl("https://example.com/cover.jpg");
        movie.setType("电影");
        movie.setGenre("动作,科幻");
        movie.setScore("8.5");
        movie.setPlays(List.of(group));

        when(cppClient.getMovieDetail(1001, null)).thenReturn(movie);
        when(videoMetaMapper.selectOne(any())).thenReturn(null);

        // ── 执行 ──
        VideoDetailVO vo = service.getVideoDetail(1001, null);

        // ── 验证 ──
        assertNotNull(vo);
        assertEquals("测试电影", vo.getTitle());
        assertNotNull(vo.getPlays(), "plays 不能为 null");
        assertEquals(1, vo.getPlays().size(), "应有 1 个播放源组");
        assertEquals(2, vo.getPlays().get(0).getUrls().size(), "应有 2 个剧集");
        assertEquals("第01集", vo.getPlays().get(0).getUrls().get(0).getEpisode());
    }

    @Test
    @DisplayName("C++ 返回 null 时，getVideoDetail 应返回 null")
    void getVideoDetail_shouldReturnNullWhenCppReturnsNull() {
        when(cppClient.getMovieDetail(9999, null)).thenReturn(null);

        VideoDetailVO vo = service.getVideoDetail(9999, null);

        assertNull(vo);
    }

    @Test
    @DisplayName("C++ 返回无 plays 的详情时，VO 的 plays 为 null")
    void getVideoDetail_shouldHandleMovieWithoutPlays() {
        CppMovieDTO movie = new CppMovieDTO();
        movie.setId(2002);
        movie.setTitle("无播放源电影");
        movie.setPlays(null);

        when(cppClient.getMovieDetail(2002, null)).thenReturn(movie);
        when(videoMetaMapper.selectOne(any())).thenReturn(null);

        VideoDetailVO vo = service.getVideoDetail(2002, null);

        assertNotNull(vo);
        assertNull(vo.getPlays(), "无播放源时 plays 应为 null");
    }
}
