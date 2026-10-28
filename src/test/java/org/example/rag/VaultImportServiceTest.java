package org.example.rag;

import org.example.config.VaultProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 迁移的"预览"阶段只遍历不落库，因此可以纯单测：
 * 隐藏目录/排除目录必须整枝跳过（否则 .obsidian 几万个文件会把扫描拖死），
 * 计数与 top-level 目录是前端确认弹窗的依据，数错了用户会误判迁移范围。
 * 真实导入的幂等/GBK/图片落地由 Postman 的 vault-import 三件套覆盖。
 */
@DisplayName("VaultImportService：迁移前扫描与路径校验")
class VaultImportServiceTest {

    @TempDir
    Path root;

    @BeforeEach
    void buildVault() throws IOException {
        Files.createDirectories(root.resolve("sub/deep"));
        Files.createDirectories(root.resolve(".obsidian"));
        Files.createDirectories(root.resolve("node_modules/pkg"));
        Files.writeString(root.resolve("a.md"), "# 根目录笔记");
        Files.writeString(root.resolve("sub/b.md"), "# 子目录笔记");
        Files.writeString(root.resolve("sub/deep/c.markdown"), "# 深层笔记");
        Files.writeString(root.resolve("sub/pic.png"), "not-really-a-png");
        Files.writeString(root.resolve(".obsidian/conf.md"), "隐藏目录不迁移");
        Files.writeString(root.resolve("node_modules/pkg/dep.md"), "依赖目录不迁移");
    }

    @Test
    @DisplayName("扫描计数：md/图片/文件夹，隐藏与排除目录整枝跳过")
    void countsVaultContents() {
        VaultImportService.VaultScanReport report = service(5000).scan(root.toString());

        assertThat(report.mdCount()).isEqualTo(3);
        assertThat(report.imageFileCount()).isEqualTo(1);
        assertThat(report.folderCount()).isEqualTo(2);      // sub、sub/deep
        assertThat(report.excludedCount()).isEqualTo(2);    // .obsidian、node_modules
        assertThat(report.topLevelFolders()).containsExactly("sub");
        assertThat(report.truncated()).isFalse();
        assertThat(report.root()).isEqualTo(root.toAbsolutePath().normalize().toString());
    }

    @Test
    @DisplayName("超过 max-files：截断为上限并置 truncated 标记")
    void truncatesWhenTooManyFiles() {
        VaultImportService.VaultScanReport report = service(1).scan(root.toString());

        assertThat(report.mdCount()).isEqualTo(1);
        assertThat(report.truncated()).isTrue();
    }

    @Test
    @DisplayName("路径为空 / 不存在 / 不是目录：一律 400（启动即失败，不先回 202）")
    void validatesRootPath() throws IOException {
        assertBadRequest(() -> service(5000).scan(null));
        assertBadRequest(() -> service(5000).scan("   "));
        assertBadRequest(() -> service(5000).scan(root.resolve("nope").toString()));
        assertBadRequest(() -> service(5000).scan(root.resolve("a.md").toString()));

        Path file = Files.writeString(root.resolve("单文件.txt"), "x");
        assertBadRequest(() -> service(5000).validateRoot(file.toString()));
    }

    private VaultImportService service(int maxFiles) {
        // 扫描路径不触碰仓储/笔记服务，可传 null（导入路径才需要）
        return new VaultImportService(null, null, null,
                new VaultProperties(maxFiles, 10 * 1024 * 1024, 10 * 1024 * 1024,
                        List.of("node_modules", "__pycache__")));
    }

    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        ResponseStatusException ex = catchThrowableOfType(callable, ResponseStatusException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(400);
    }
}
