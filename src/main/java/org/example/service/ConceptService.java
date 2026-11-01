package org.example.service;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.example.config.ConceptProperties;
import org.example.domain.Concept;
import org.example.domain.ConceptNote;
import org.example.domain.ConceptTopic;
import org.example.domain.Note;
import org.example.domain.Topic;
import org.example.rag.ConceptExtractor;
import org.example.repository.ConceptNoteRepository;
import org.example.repository.ConceptRepository;
import org.example.repository.ConceptTopicRepository;
import org.example.repository.NoteRepository;
import org.example.repository.TopicRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 概念层：把一篇笔记变成「概念 + 提及」，并把概念挂到主题上。
 *
 * <p>设计上有三个刻意的选择，都是前面实验推出来的（doc/08）：
 * <ul>
 *   <li><b>抽取按篇、增量、自包含</b>：每篇只读自己，不需要全量重抽。
 *       所以"最后只汇总"成立——汇总只剩重算主题，那是本地计算，不花模型调用。</li>
 *   <li><b>归并在写入时做</b>：概念身份必须当场确定。留到事后统一归并的话，
 *       那次归并就是全量的，"每篇独立处理"的前提就没了。</li>
 *   <li><b>主题来自结构证据</b>：v1 只用文件夹（E2）。语义聚类实测 46% 是错的，
 *       等有判别器再接，接的时候只是多一种 source，不动表结构。</li>
 * </ul>
 */
@Service
public class ConceptService {

    private static final Logger log = LoggerFactory.getLogger(ConceptService.class);

    /** 兜底主题名：没有文件夹的概念都归它，它在图算法里当 NULL 处理 */
    private static final String LOOSE_TOPIC = "未分类";

    /**
     * 主题生成规则版本。规则一改（文件夹折叠、加入语义聚类…）就 +1，
     * 库里旧规则生成的主题就会被识别出来并重算，不需要手工清库。
     */
    private static final int TOPIC_RULE_VERSION = ConceptProperties.TOPIC_RULE_VERSION;

    /**
     * 抽名字的提示词。只做一件事——不判类型、不给说明、不指认块。
     * 实测（doc/08 §5.5）：2B 在"多约束合并成一次调用"时会过度保守或自由发挥，
     * 拆成单步之后两次运行结果完全一致。这是整个管线里唯一一次模型调用。
     */
    private static final SystemMessage PROMPT = SystemMessage.from("""
            从笔记结构里抽出概念名，只输出 JSON。

            概念是可复用的知识名词：技术、算法、模型、方法、理论、指标、协议。
            不要抽：小节名（核心思想/优点/边界/示例/总结/分类）、代码、命令、变量名、半句话。
            名字必须用原文写法，不要翻译、不要改写。
            最多 15 个。

            输出格式：{"entities":["概念1","概念2"]}""");

    /**
     * 注意是 {@code conceptChatModel}，不是对话用的 {@code chatModel}。
     * 对话模型是思考模型且没有输出上限，拿来做抽取会一直"思考"到超时，队列会卡死
     * （见 AiModelConfig#conceptChatModel 的注释）。
     */
    private final ChatModel chatModel;
    private final NoteRepository noteRepo;
    private final ConceptRepository conceptRepo;
    private final ConceptNoteRepository mentionRepo;
    private final TopicRepository topicRepo;
    private final ConceptTopicRepository conceptTopicRepo;
    private final ConceptProperties props;
    private final ConceptThemeService themeService;
    private final TransactionTemplate tx;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public ConceptService(@Qualifier("conceptChatModel") ChatModel chatModel,
                          NoteRepository noteRepo,
                          ConceptRepository conceptRepo,
                          ConceptNoteRepository mentionRepo,
                          TopicRepository topicRepo,
                          ConceptTopicRepository conceptTopicRepo,
                          ConceptProperties props,
                          ConceptThemeService themeService,
                          TransactionTemplate tx) {
        this.themeService = themeService;
        this.chatModel = chatModel;
        this.noteRepo = noteRepo;
        this.conceptRepo = conceptRepo;
        this.mentionRepo = mentionRepo;
        this.topicRepo = topicRepo;
        this.conceptTopicRepo = conceptTopicRepo;
        this.props = props;
        this.tx = tx;
    }

    // ===================== 写：单篇抽取 =====================

    /** 抽取结果，供队列与回填端点数进度 */
    public record ExtractResult(String noteId, boolean skipped, int names, int kept, String reason) {
    }

    /**
     * 抽一篇。内容没变就直接跳过——用户点两次保存但没改字，不该产生两次模型调用。
     * 失败只记日志并返回原因，绝不向上抛：保存已经成功了，抽取失败是次要故障。
     *
     * <p>模型调用发生在事务内（占一个连接约一秒）。这里可以接受，因为队列是单线程、
     * H2 是本地库；换成远程数据库时要把调用挪到事务外，否则连接池会被慢模型拖住。
     */
    @Transactional
    public ExtractResult extract(String noteId) {
        if (!props.enabled()) {
            return new ExtractResult(noteId, true, 0, 0, "概念抽取已关闭");
        }
        Note note = noteRepo.findById(noteId).orElse(null);
        if (note == null) {
            return new ExtractResult(noteId, true, 0, 0, "笔记不存在");
        }
        String content = note.getContent() == null ? "" : note.getContent();
        if (content.isBlank()) {
            mentionRepo.deleteByNoteId(noteId);
            return new ExtractResult(noteId, true, 0, 0, "正文为空");
        }
        String hash = hash(note.getTitle() + "\n" + content);
        if (hash.equals(note.getConceptHash())) {
            return new ExtractResult(noteId, true, 0, 0, "内容未变");
        }
        if (content.length() > props.maxContentChars()) {
            content = content.substring(0, props.maxContentChars());
        }

        List<ConceptExtractor.Block> blocks = ConceptExtractor.blocks(content);
        if (blocks.isEmpty()) {
            return new ExtractResult(noteId, true, 0, 0, "没有结构块");
        }
        String prompt = "笔记标题：" + note.getTitle() + "\n\n结构骨架：\n"
                + ConceptExtractor.skeleton(blocks);

        List<String> names;
        try {
            ChatResponse response = chatModel.chat(PROMPT, UserMessage.from(prompt));
            names = ConceptExtractor.parseNames(response.aiMessage().text(), mapper);
        } catch (Exception e) {
            log.warn("笔记 {} 概念抽取失败（模型调用或返回解析）：{}", noteId, String.valueOf(e.getMessage()));
            return new ExtractResult(noteId, true, 0, 0, "模型调用或解析失败");
        }

        // 锚点校验：回不到原文的名字一律丢弃（模型会偷翻译、会从段落里造半句话）
        List<String> anchors = ConceptExtractor.anchors(blocks);
        // 锚点校验通过后，把名字收敛回原文写法：模型只用来"指认"，
        // 落库的名字必须是原文里的那个说法
        List<String> kept = new ArrayList<>();
        for (String name : names) {
            if (ConceptExtractor.anchored(name, anchors)) {
                String canonical = ConceptExtractor.canonicalName(name, anchors);
                if (canonical.length() > 1 && !kept.contains(canonical)) {
                    kept.add(canonical);
                }
            }
        }

        writeMentions(note, kept, blocks);
        // 指纹只做定向列更新，绝不 save(note)：writeMentions 里的 deleteByNoteId
        // （clearAutomatically）已把 note 踢出持久化上下文，save 的 merge 会把
        // 事务开头读到的旧内容整行写回——模型调用期间用户的再次保存就被静默回退了。
        noteRepo.updateConceptHash(note.getId(), hash);
        log.info("笔记 {} 概念抽取：模型给 {} 个，锚点校验后保留 {}", noteId, names.size(), kept.size());
        return new ExtractResult(noteId, false, names.size(), kept.size(), "ok");
    }

    /** 整篇替换提及，并把名字并进概念表 */
    private void writeMentions(Note note, List<String> names, List<ConceptExtractor.Block> blocks) {
        mentionRepo.deleteByNoteId(note.getId());

        Map<String, Concept> cache = new HashMap<>();
        for (Concept c : conceptRepo.findAll()) {
            cache.put(c.getNormKey(), c);
        }
        Set<String> touched = new HashSet<>();
        // 同一篇里去重：模型偶尔会重复给出同一个名字，或给出两种写法而它们被归并到同一个概念。
        // 不去重就会插入两条 (concept_id, note_id) 相同的边，撞主键 → 整篇事务回滚 →
        // 指纹写不进去 → 这篇笔记永远处于"待抽取"，每次回填都失败一次。
        Set<String> usedKeys = new HashSet<>();
        Set<String> usedConceptIds = new HashSet<>();
        Instant now = Instant.now();

        for (String name : names) {
            if (!usedKeys.add(ConceptExtractor.normKey(name))) {
                continue;
            }
            Concept concept = resolve(name, cache);
            if (!usedConceptIds.add(concept.getId())) {
                continue;
            }
            ConceptNote mention = new ConceptNote();
            mention.setConceptId(concept.getId());
            mention.setNoteId(note.getId());
            // 说明按标题归属，拿不到就留空——留空比错的好
            ConceptExtractor.attribute(name, blocks)
                    .map(ConceptExtractor::cleanDescription)
                    .filter(s -> !s.isBlank())
                    .ifPresent(mention::setDescription);
            mention.setKind(isHeadingMention(name, blocks) ? "explicit" : "implicit");
            mentionRepo.save(mention);
            touched.add(concept.getNormKey());
        }
        mentionRepo.flush();

        // 提及数变了，覆盖笔记数要跟着变；只重算受影响的，不扫全表
        for (String key : touched) {
            Concept c = cache.get(key);
            if (c == null) {
                continue;
            }
            int count = mentionRepo.findByConceptId(c.getId()).size();
            c.setNoteCount(count);
            c.setLastSeenAt(now);
            conceptRepo.save(c);
        }
    }

    /**
     * 写入时归并：归一化同名 → 字面包含 → 新建。
     *
     * <p>字面包含这条有硬条件（短名 ≥3 字、长度差 ≤4）：放开到 2 字会误合并——
     * 实测 `网络` ⊂ `卷积神经网络`、`语法` ⊂ `语法分析` 都被错并过，那是两个概念。
     * 代价是 `重排` 这种两字缩写不会并进 `检索后重排`，宁可留两个也不要把不同的东西并掉。
     */
    private Concept resolve(String name, Map<String, Concept> cache) {
        String key = ConceptExtractor.normKey(name);
        Concept hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        for (Concept c : cache.values()) {
            if (literalMatch(key, c.getNormKey())) {
                cache.put(key, c);
                return c;
            }
        }
        Concept created = new Concept();
        created.setName(name);
        created.setNormKey(key);
        created.setNoteCount(0);
        created.setFirstSeenAt(Instant.now());
        created = conceptRepo.save(created);
        cache.put(key, created);
        return created;
    }

    /**
     * 主题名 = 文件夹的第一段路径。
     *
     * <p>不用完整路径：`深度学习/DRL` 和 `计算机基础课程/编译原理` 各自只有 2–3 篇笔记，
     * 各自成主题的话大部分主题只有一两个概念，主题层就退化成文件夹列表。
     * 折到顶层后 `深度学习` 拿到 42 个概念、`计算机基础课程` 拿到 17 个，
     * 才算"粗粒度主题"。更细的层次由笔记的文件夹树提供，不在这一层重复。
     */
    private static String topicFolder(String folder) {
        if (folder == null || folder.isBlank()) {
            return LOOSE_TOPIC;
        }
        String f = folder.replace('\\', '/').trim();
        int i = f.indexOf('/');
        String root = (i > 0 ? f.substring(0, i) : f).trim();
        return root.isEmpty() ? LOOSE_TOPIC : root;
    }

    private static boolean literalMatch(String a, String b) {
        if (a.isEmpty() || b.isEmpty() || a.equals(b)) {
            return false;
        }
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        if (shorter.length() < 3 || longer.length() - shorter.length() > 4) {
            return false;
        }
        return longer.contains(shorter);
    }

    private static boolean isHeadingMention(String name, List<ConceptExtractor.Block> blocks) {
        String n = ConceptExtractor.normKey(name);
        for (ConceptExtractor.Block b : blocks) {
            if (b.isHeading() && ConceptExtractor.normKey(b.text()).contains(n)) {
                return true;
            }
        }
        return false;
    }

    // ===================== 汇总：主题 =====================

    /**
     * 是否需要重算主题层。
     *
     * <p>判断依据是"有多少概念还没有归属"，不是"主题数是否为 0"。
     * 后者会漏掉真实发生过的情况：回填抽完 34 篇之后主题从没重算过，
     * 库里 103 个概念只有 14 条主题边，页面上 89 个概念全落进未分类，
     * 而主题表里明明有 3 个主题，看上去一切正常。
     */
    public boolean needsTopicRebuild() {
        long concepts = conceptRepo.count();
        if (concepts == 0) {
            return false;
        }
        if (topicRepo.count() == 0) {
            return true;
        }
        // 主题在、但可能是旧规则留下的（来源换了、折叠方式变了之类）
        if (topicRepo.countStaleTopics(TOPIC_RULE_VERSION) > 0) {
            return true;
        }
        if (isLlmThemeMode()) {
            // 模型模式下"缺边"由增量归类处理（一次调用），不值得触发全量重算
            return false;
        }
        return conceptTopicRepo.countDistinctConcepts() < concepts;
    }

    private boolean isLlmThemeMode() {
        return "llm".equalsIgnoreCase(props.themeMode());
    }

    /**
     * 主题层同步：按模式选择做法。
     *
     * <p>模型模式：第一次做全量（按概念总数付模型调用的钱），之后只做增量——
     * 把新出现、还没有归属的概念交给模型归类，一次调用解决。
     *
     * <p>文件夹模式：全量重算，本地计算、毫秒级。
     */
    public void syncTopics() {
        if (isLlmThemeMode()) {
            // 规则变了（来源换了、版本号变了）就必须全量重来：
            // 增量归类只会把新概念挂到旧主题上，旧主题本身是按旧规则生成的
            boolean stale = topicRepo.countStaleTopics(TOPIC_RULE_VERSION) > 0;
            if (topicRepo.count() == 0 || stale) {
                themeService.rebuildAll();
            } else {
                themeService.assignPending();
            }
            return;
        }
        rebuildByFolder();
    }

    /** 手动触发：llm 模式是全量重算，文件夹模式是本地重算 */
    public Map<String, Object> rebuildTopics() {
        if (isLlmThemeMode()) {
            return themeService.rebuildAll();
        }
        return rebuildByFolder();
    }

    /**
     * 重算主题层（文件夹模式）。全量重建，本地计算、不花模型调用，几百个概念是毫秒级。
     *
     * <p>v1 的主题来自文件夹：人分的组，语义上不会错，代价是可能很稀疏
     * （实测 14 个文件夹里 12 个只有一篇笔记）。语义聚类等有判别器再接。
     *
     * <p>清表与重写必须在同一事务里原子完成，中途崩溃不能留下「边已删、主题没建」的残缺。
     * 刻意用 TransactionTemplate 而不是 @Transactional：syncTopics()/rebuildTopics()
     * 都是本类内部调用，注解会被 Spring 自调用绕过代理。
     */
    public Map<String, Object> rebuildByFolder() {
        return tx.execute(status -> {
            conceptTopicRepo.deleteAllInBatch();
            topicRepo.deleteAllInBatch();

            // 一次性把提及和笔记都读进内存：原来每个概念查一次提及，
            // 一千个概念就是一千次查询，主题层会变成整页最慢的一步。
            Map<String, String> folderByNote = new HashMap<>();
            for (Note n : noteRepo.findAll()) {
                folderByNote.put(n.getId(), topicFolder(n.getFolder()));
            }
            Map<String, List<ConceptNote>> mentionsByConcept = new HashMap<>();
            for (ConceptNote m : mentionRepo.findAll()) {
                mentionsByConcept.computeIfAbsent(m.getConceptId(), k -> new ArrayList<>()).add(m);
            }

            Map<String, Topic> topics = new LinkedHashMap<>();
            Topic loose = newTopic(LOOSE_TOPIC, "folder", true);
            topics.put(LOOSE_TOPIC, loose);

            Map<String, Integer> memberCount = new HashMap<>();
            Set<String> assigned = new HashSet<>();

            for (Concept c : conceptRepo.findAll()) {
                Map<String, Integer> byFolder = new LinkedHashMap<>();
                for (ConceptNote cn : mentionsByConcept.getOrDefault(c.getId(), List.of())) {
                    String folder = folderByNote.getOrDefault(cn.getNoteId(), LOOSE_TOPIC);
                    byFolder.merge(folder, 1, Integer::sum);
                }
                if (byFolder.isEmpty()) {
                    addEdge(c, loose, 1.0, true);
                    memberCount.merge(loose.getId(), 1, Integer::sum);
                    assigned.add(c.getId());
                    continue;
                }
                List<Map.Entry<String, Integer>> ranked = new ArrayList<>(byFolder.entrySet());
                ranked.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());
                int total = ranked.stream().mapToInt(Map.Entry::getValue).sum();
                for (int i = 0; i < ranked.size(); i++) {
                    Map.Entry<String, Integer> e = ranked.get(i);
                    Topic t = LOOSE_TOPIC.equals(e.getKey())
                            ? loose
                            : topics.computeIfAbsent(e.getKey(), k -> newTopic(k, "folder", false));
                    addEdge(c, t, e.getValue() / (double) total, i == 0);
                    if (i == 0) {
                        memberCount.merge(t.getId(), 1, Integer::sum);
                    }
                    assigned.add(c.getId());
                }
            }

            for (Topic t : topics.values()) {
                t.setConceptCount(memberCount.getOrDefault(t.getId(), 0));
                t.setRuleVersion(TOPIC_RULE_VERSION);
                topicRepo.save(t);
            }
            log.info("主题重算完成：{} 个主题，覆盖 {} 个概念（规则 v{}）",
                    topics.size(), assigned.size(), TOPIC_RULE_VERSION);
            return Map.of("topics", topics.size(), "concepts", assigned.size());
        });
    }

    private Topic newTopic(String name, String source, boolean structuralNull) {
        Topic t = new Topic();
        t.setName(name);
        t.setSource(source);
        t.setStructuralNull(structuralNull);
        return topicRepo.save(t);
    }

    private void addEdge(Concept c, Topic t, double weight, boolean primary) {
        ConceptTopic edge = new ConceptTopic();
        edge.setConceptId(c.getId());
        edge.setTopicId(t.getId());
        edge.setWeight(weight);
        edge.setPrimary(primary);
        conceptTopicRepo.save(edge);
    }

    // ===================== 读：给前端的形状 =====================

    /**
     * 概念层总览。返回结构与前端 `conceptLayer.json` 完全一致，
     * 这样前端可以从静态文件无缝切到接口，不用改渲染逻辑。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        List<Concept> concepts = conceptRepo.findAll();
        List<ConceptNote> mentions = mentionRepo.findAll();
        Map<String, Note> noteById = new HashMap<>();
        for (Note n : noteRepo.findAll()) {
            noteById.put(n.getId(), n);
        }
        Map<String, List<ConceptNote>> byConcept = new HashMap<>();
        for (ConceptNote m : mentions) {
            byConcept.computeIfAbsent(m.getConceptId(), k -> new ArrayList<>()).add(m);
        }
        Map<String, List<ConceptTopic>> edgesByConcept = new HashMap<>();
        for (ConceptTopic e : conceptTopicRepo.findAll()) {
            edgesByConcept.computeIfAbsent(e.getConceptId(), k -> new ArrayList<>()).add(e);
        }
        Map<String, Topic> topicById = new HashMap<>();
        for (Topic t : topicRepo.findAll()) {
            topicById.put(t.getId(), t);
        }

        List<Map<String, Object>> conceptViews = new ArrayList<>();
        Map<String, List<String>> byTopic = new LinkedHashMap<>();
        List<String> loose = new ArrayList<>();
        int explained = 0;

        for (Concept c : concepts) {
            List<ConceptNote> ms = byConcept.getOrDefault(c.getId(), List.of());
            // 说明取最长的一条：同一概念在多篇里解释深度不同，最完整的那个最有代表性
            ConceptNote best = ms.stream()
                    .filter(m -> m.getDescription() != null && !m.getDescription().isBlank())
                    .max(Comparator.comparingInt(m -> m.getDescription().length()))
                    .orElse(ms.isEmpty() ? null : ms.get(0));
            Note src = best == null ? null : noteById.get(best.getNoteId());
            List<ConceptTopic> edges = edgesByConcept.getOrDefault(c.getId(), List.of());
            // 兜底桶不算归属：它的 id 不出现在 topics 里，
            // 这样"没有归属"在前端就是 topics 为空，两种口径一致
            List<String> topicIds = new ArrayList<>();
            String primary = null;
            for (ConceptTopic e : edges) {
                Topic t = topicById.get(e.getTopicId());
                if (t == null) {
                    continue;
                }
                if (t.isStructuralNull()) {
                    continue;
                }
                topicIds.add(e.getTopicId());
                if (e.isPrimary()) {
                    primary = e.getTopicId();
                }
            }

            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", c.getName());
            view.put("desc", best == null || best.getDescription() == null ? "" : best.getDescription());
            view.put("noteId", src == null ? "" : src.getId());
            view.put("noteTitle", src == null ? "未命名" : src.getTitle());
            view.put("folder", src == null || src.getFolder() == null ? "" : src.getFolder());
            view.put("date", src == null || src.getCreatedAt() == null ? "" : src.getCreatedAt().toString().substring(0, 10));
            view.put("topics", topicIds);
            view.put("primary", primary);
            conceptViews.add(view);
            if (best != null && best.getDescription() != null && !best.getDescription().isBlank()) {
                explained++;
            }
            for (String tid : topicIds) {
                byTopic.computeIfAbsent(tid, k -> new ArrayList<>()).add(c.getName());
            }
            if (topicIds.isEmpty()) {
                // 只挂在兜底桶上的、或一条边都没有的，都算未分类
                loose.add(c.getName());
            }
        }

        List<Map<String, Object>> topicViews = new ArrayList<>();
        for (Topic t : topicRepo.findAllByOrderByConceptCountDescNameAsc()) {
            if (t.isStructuralNull()) {
                continue;
            }
            List<String> members = byTopic.getOrDefault(t.getId(), List.of());
            Map<String, Object> tv = new LinkedHashMap<>();
            tv.put("id", t.getId());
            tv.put("name", t.getName());
            tv.put("evidence", t.getSource());
            tv.put("concepts", members);
            int withDesc = 0;
            for (Map<String, Object> cv : conceptViews) {
                if (members.contains(cv.get("name")) && !"".equals(cv.get("desc"))) {
                    withDesc++;
                }
            }
            tv.put("depth", members.isEmpty() ? 0 : (double) withDesc / members.size());
            topicViews.add(tv);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("notes", noteById.size());
        meta.put("concepts", concepts.size());
        meta.put("explained", explained);
        meta.put("topics", topicViews.size());
        meta.put("uncategorized", new LinkedHashSet<>(loose).size());
        meta.put("source", isLlmThemeMode()
                ? "本地抽取 · 写入时归并 · 主题由模型按语义分组"
                : "本地抽取 · 写入时归并 · 主题来自文件夹（结构证据）");
        meta.put("generatedAt", Instant.now().toString());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meta", meta);
        out.put("topics", topicViews);
        out.put("concepts", conceptViews);
        out.put("uncategorized", new ArrayList<>(new LinkedHashSet<>(loose)));
        return out;
    }

    public long conceptCount() {
        return conceptRepo.count();
    }

    public long topicCount() {
        return topicRepo.count();
    }

    /** 概念详情：说明 + 出处 + 主题 + 同主题兄弟 */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> detail(String conceptId) {
        return conceptRepo.findById(conceptId).map(c -> {
            List<ConceptNote> ms = mentionRepo.findByConceptId(c.getId());
            List<Map<String, Object>> sources = new ArrayList<>();
            for (ConceptNote m : ms) {
                noteRepo.findById(m.getNoteId()).ifPresent(n -> {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("noteId", n.getId());
                    s.put("title", n.getTitle());
                    s.put("folder", n.getFolder());
                    s.put("description", m.getDescription());
                    sources.add(s);
                });
            }
            List<String> topics = conceptTopicRepo.findByConceptId(c.getId()).stream()
                    .map(ConceptTopic::getTopicId).toList();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", c.getId());
            out.put("name", c.getName());
            out.put("noteCount", c.getNoteCount());
            out.put("firstSeenAt", c.getFirstSeenAt());
            out.put("lastSeenAt", c.getLastSeenAt());
            out.put("sources", sources);
            out.put("topics", topics);
            return out;
        });
    }

    private static String hash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}
