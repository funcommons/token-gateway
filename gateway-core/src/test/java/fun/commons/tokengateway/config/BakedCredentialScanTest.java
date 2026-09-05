package fun.commons.tokengateway.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G3 验收: 仓库内无明文凭据 (yml/properties 禁 baked 密钥).
 *
 * <p>扫描所有模块 main 资源 (yml/yaml/properties):
 * <ul>
 *   <li>JWT 形态 (eyJ 三段式) 不得出现 —— 曾发生: internal-token 直接配 baked JWT</li>
 *   <li>secret/key/token/password/passphrase 键的值必须为空或 env 占位符; env 占位
 *       允许非密钥默认值 (如 localhost), 但不得像真实密钥 (≥16 位连续凭据字符)</li>
 * </ul>
 * 文档与 scripts 不在扫描范围 (示例值允许)。
 */
class BakedCredentialScanTest {

    private static final Pattern JWT_PATTERN =
            Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}");

    /** 敏感键值行: key: value (宽松匹配含 - 连写与内联注释). */
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i)^\\s*(?:#\\s*)?[a-zA-Z-]*(?:secret|key|token|password|passphrase)[a-zA-Z-]*\\s*:\\s*(.+?)\\s*$");

    /** 像真实密钥的字符串: ≥16 位连续凭据字符 (base64/hex/JWT 片段). */
    private static final Pattern LOOKS_LIKE_SECRET = Pattern.compile("^[A-Za-z0-9+/=_-]{16,}$");

    @Test
    void noBakedJwtInConfigResources() throws IOException {
        List<String> hits = scan(JWT_PATTERN.asMatchPredicate());
        assertThat(hits).as("配置资源中不得出现 baked JWT (须 env 注入): %s", hits).isEmpty();
    }

    @Test
    void noPlaintextSecretValuesInConfigResources() throws IOException {
        List<String> hits = scan(line -> {
            Matcher m = SENSITIVE_KEY.matcher(line);
            if (!m.find()) {
                return false;
            }
            String value = m.group(1).trim();
            // 空值 / env 占位 / dev- 占位 / 列表与块标量起始 / URL 默认值放行
            if (value.isEmpty() || value.startsWith("${") || value.startsWith("dev-")
                    || value.startsWith("|") || value.startsWith(">")
                    || value.startsWith("http://") || value.startsWith("https://")) {
                return false;
            }
            // env 占位带默认值: 取默认值判定 (如 ${LOTASK_SIGN_KEY:} 默认空 → 放行)
            Matcher env = Pattern.compile("^\\$\\{[^:}]*:\\s*([^}]+)}\\s*$").matcher(value);
            if (env.find()) {
                String dflt = env.group(1).trim();
                return !dflt.isEmpty() && LOOKS_LIKE_SECRET.matcher(dflt).matches();
            }
            return LOOKS_LIKE_SECRET.matcher(value).matches();
        });
        assertThat(hits).as("配置资源中不得出现明文密钥赋值 (须 env 注入): %s", hits).isEmpty();
    }

    private List<String> scan(java.util.function.Predicate<String> linePredicate) throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(repoRoot())) {
            walk.filter(p -> {
                        String n = p.getFileName().toString();
                        String s = p.toString();
                        return (n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".properties"))
                                && s.contains("src" + java.io.File.separator + "main")
                                && !s.contains("target") && !s.contains("node_modules");
                    })
                    .forEach(p -> {
                        try {
                            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                            for (int i = 0; i < lines.size(); i++) {
                                if (linePredicate.test(lines.get(i))) {
                                    hits.add(repoRoot().relativize(p) + ":" + (i + 1)
                                            + " [" + lines.get(i).trim() + "]");
                                }
                            }
                        } catch (IOException ignored) {
                            // 不可读文件跳过
                        }
                    });
        }
        return hits;
    }

    /** 仓库根: 测试工作目录为 gateway-core/, 根 = 其父目录 (含 app/ 判据). */
    private Path repoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        return Files.isDirectory(cwd.resolve("app/src/main/resources")) ? cwd : cwd.getParent();
    }
}
