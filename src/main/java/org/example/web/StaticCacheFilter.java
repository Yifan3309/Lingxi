package org.example.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 前端静态资源的缓存策略。
 *
 * <p><b>为什么必须管：</b>入口 HTML 与带 hash 的 chunk 是两回事。chunk 文件名带内容 hash，
 * 可以永久缓存；但 {@code index.html} 指向哪个入口 chunk，取决于当次构建。
 * 如果浏览器缓存了旧 {@code index.html}，而服务端（jar）已经升级成新构建，
 * 页面会拿着**旧构建里某个动态 chunk 的新 hash** 去请求——那个文件在新 jar 里根本不存在，
 * 于是「Failed to fetch dynamically imported module」，路由直接加载不出来。
 *
 * <p>实测踩到过：切换到另一个后端实例（新版 jar）后，浏览器缓存的入口 chunk 引用了
 * 新 hash 的图谱 chunk，服务端只有旧 hash 的版本 → 404 → 图谱页打不开。
 * 这跟数据量、跟 500 篇文档的压力测试都无关，是缓存与构建版本错配。
 *
 * <p>策略：
 * <ul>
 *   <li>{@code /} 与 {@code /index.html}：{@code no-store}——每次都拿当前的，
 *       它决定用哪个入口 chunk，必须永远是最新的；</li>
 *   <li>{@code /assets/**}：{@code immutable} + 一年——文件名带内容 hash，
 *       内容变了文件名就变，缓存可以放心留着。</li>
 * </ul>
 *
 * <p>不碰其它路径：SSE（{@code /api/chat/stream}）等动态响应不能被加缓存头。
 */
@Component
public class StaticCacheFilter extends OncePerRequestFilter {

    private static final String ASSET_PREFIX = "/assets/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri != null) {
            if (uri.startsWith(ASSET_PREFIX)) {
                response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
            } else if ("/".equals(uri) || uri.endsWith("/index.html")) {
                response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
                response.setHeader("Pragma", "no-cache");
                response.setDateHeader("Expires", 0);
            }
        }
        chain.doFilter(request, response);
    }
}
