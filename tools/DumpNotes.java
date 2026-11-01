import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 一次性分析工具（不进应用构建）：把 H2 里的笔记导出成 JSONL，供外部脚本做抽取实验。
 * 运行（仓库根目录）：java -cp "~/.m2/.../h2-2.3.232.jar" tools/DumpNotes.java <输出路径>
 */
public class DumpNotes {

    public static void main(String[] args) throws Exception {
        String url = "jdbc:h2:file:./data/notes;MODE=PostgreSQL;AUTO_SERVER=TRUE";
        Path out = Path.of(args.length > 0 ? args[0] : "target/notes-dump.jsonl");
        Files.createDirectories(out.toAbsolutePath().getParent());

        int count = 0;
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "select id, title, tags, folder, content, created_at, updated_at from note");
             BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            while (rs.next()) {
                w.write("{\"id\":\"" + esc(rs.getString("id"))
                        + "\",\"title\":\"" + esc(rs.getString("title"))
                        + "\",\"tags\":\"" + esc(rs.getString("tags"))
                        + "\",\"folder\":\"" + esc(rs.getString("folder"))
                        + "\",\"createdAt\":\"" + esc(String.valueOf(rs.getString("created_at")))
                        + "\",\"content\":\"" + esc(rs.getString("content"))
                        + "\"}");
                w.newLine();
                count++;
            }
        }
        System.out.println("导出 " + count + " 篇 -> " + out.toAbsolutePath());
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        b.append(String.format("\\u%04x", (int) ch));
                    } else {
                        b.append(ch);
                    }
                }
            }
        }
        return b.toString();
    }
}
