#!/usr/bin/env bash
# 不依赖 Maven 的编译校验 + 纯逻辑自检。
#
# 这台机器上没装 Maven，但 ~/.m2 里的依赖 jar 是全的，所以直接拿 javac 编。
# 433 个 jar 拼成的 classpath 超出命令行长度上限，必须走 @argfile。
#
# 用法：bash tools/compile-check.sh
set -e
cd "$(dirname "$0")/.."

MAIN_FILES=(
  src/main/java/org/example/domain/Concept.java
  src/main/java/org/example/domain/ConceptNote.java
  src/main/java/org/example/domain/Topic.java
  src/main/java/org/example/domain/ConceptTopic.java
  src/main/java/org/example/domain/Note.java
  src/main/java/org/example/repository/ConceptRepository.java
  src/main/java/org/example/repository/ConceptNoteRepository.java
  src/main/java/org/example/repository/TopicRepository.java
  src/main/java/org/example/repository/ConceptTopicRepository.java
  src/main/java/org/example/config/ConceptProperties.java
  src/main/java/org/example/config/AiModelConfig.java
  src/main/java/org/example/rag/ConceptExtractor.java
  src/main/java/org/example/service/ConceptService.java
  src/main/java/org/example/service/ConceptQueue.java
  src/main/java/org/example/service/ConceptThemeService.java
  src/main/java/org/example/service/ConceptThemeBootstrap.java
  src/main/java/org/example/service/NoteService.java
  src/main/java/org/example/web/ConceptController.java
  src/main/java/org/example/web/ConceptAdminController.java
)

python - "${MAIN_FILES[@]}" <<'PY'
import glob, os, sys
jars = [p.replace('\\', '/') for p in
        glob.glob(os.path.expanduser('~/.m2/repository/**/*.jar'), recursive=True)
        if 'sources' not in p and 'javadoc' not in p]
os.makedirs('target/cc', exist_ok=True)
cp = ';'.join(['target/cc', 'target/classes'] + jars)
# -parameters 必须带上：Spring Boot 的 Maven 构建默认开启它，
# 少了它 @RequestParam(defaultValue=...) 这类按参数名绑定的注解会在运行时报
# "parameter name information not available via reflection"。
open('target/cc-main.txt', 'w', encoding='utf-8').write('\n'.join(
    ['-encoding UTF-8', '-parameters', '-nowarn', '-d target/cc', '-cp ' + cp] + sys.argv[1:]))
open('target/cc-probe.txt', 'w', encoding='utf-8').write('\n'.join(
    ['-encoding UTF-8', '-parameters', '-nowarn', '-d target/cc', '-cp ' + cp, 'tools/ConceptProbe.java']))
open('target/cc-run.txt', 'w', encoding='utf-8').write('\n'.join(
    ['-cp ' + cp, 'ConceptProbe']))
print(f'classpath: {len(jars)} 个 jar')
PY

export JAVA_TOOL_OPTIONS="-Duser.language=en -Dstdout.encoding=UTF-8"

# 自检用的样例笔记：脚本自己生成，不依赖任何手工准备的文件
cat > target/probe-note.md <<'NOTE'
把最近这条链路整段过一遍。

## 1.Query Compression（查询压缩）

* **核心思想**：先把指代补全、把口语改成书面表述，再去检索，命中的概率明显高。
* **代价**：多一次模型调用，链路尾延迟涨 200ms 左右。

## 元数据过滤

**核心思想**：向量检索之前先按字段筛一遍，把范围从全库缩到某个项目、某个时间段。

## 动态跳过检索

**核心思想**：不是每个问题都值得查库，闲聊和追问直接交给模型答。
NOTE

echo "== 编译主代码 =="
javac @target/cc-main.txt
echo "== 编译自检 =="
javac @target/cc-probe.txt
echo "== 运行自检 =="
java @target/cc-run.txt
