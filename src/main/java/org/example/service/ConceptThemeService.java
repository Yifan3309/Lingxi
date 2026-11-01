package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.example.config.ConceptProperties;
import org.example.domain.Concept;
import org.example.domain.ConceptNote;
import org.example.domain.ConceptTopic;
import org.example.domain.Note;
import org.example.domain.Topic;
import org.example.repository.ConceptNoteRepository;
import org.example.repository.ConceptRepository;
import org.example.repository.ConceptTopicRepository;
import org.example.repository.NoteRepository;
import org.example.repository.TopicRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主题层：由模型按语义把概念分组。
 *
 * <p><b>为什么不用向量聚类。</b>实测过（doc/08 §6.5）：E5 这类短文本向量在中文概念名上
 * 区分度不够，簇里会混进「交叉熵 + FCB + 贝尔曼最优方程 + TCP/IP」这种组合。
 * 而模型的语义判断明显更准——同一批概念，模型把
 * {@code Query Compression、查询路由、检索后重排、元数据增强、元数据过滤、动态跳过检索}
 * 正确地放进同一个主题。
 *
 * <p><b>为什么不用文件夹。</b>文件夹是结构证据，语义上不会错，但太机械：
 * 「深度学习」这一个文件夹里混着 CNN、强化学习、符号约定，其实是三个主题。
 * 文件夹版保留为回退（{@code app.concept.theme-mode=folder}）——
 * 模型不可用时至少有个能用的分组。
 *
 * <p><b>约束放在后置校验里，不写进提示词。</b>2B 模型在提示词里每多一条硬约束，
 * 产出就掉一截（doc/08 §5.5 的实测）。所以提示词只说"按主题分组"，
 * 剩下的由代码兜：成员必须是真实概念名、不能是主题名、单个主题有规模上限、
 * 一个概念只属于一个主题。
 */
@Service
public class ConceptThemeService {

    private static final Logger log = LoggerFactory.getLogger(ConceptThemeService.class);

    /** 最多分几轮。模型漏掉的概念不重试，直接进未分类，所以轮数就是批数 */
    private static final int MAX_ROUNDS = 32;

    /**
     * 分组提示词。只说一件事：按主题分组，并把已有主题清单给它以便复用。
     *
     * <p>刻意不写"每个主题最多 N 个""不要输出主题名当成员"这类约束——
     * 写进去模型会变得保守，而且这些规则用代码校验更可靠。
     */
    private static final SystemMessage PROMPT = SystemMessage.from("""
            你是知识整理助手。给一批概念，按主题把它们分组，只输出 JSON。

            要求：
            1. 分成 2 到 6 组，每组 2 到 10 个概念
            2. 组名用中文，8 字以内，是这一组的共同主题（如"强化学习"、"内存管理"、"检索增强"）
            3. 一个概念只能属于一组；实在没归属就放进最接近的一组
            4. 按概念本身的含义分组，不要按来源笔记分
            5. 如果给了已有主题清单，优先归入语义最接近的已有主题，并沿用它的名字

            输出格式：{"groups":[{"name":"组名","members":["概念1","概念2"]}]}""");

    private final ChatModel chatModel;
    private final ConceptRepository conceptRepo;
    private final ConceptNoteRepository mentionRepo;
    private final TopicRepository topicRepo;
    private final ConceptTopicRepository edgeRepo;
    private final NoteRepository noteRepo;
    private final ConceptProperties props;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper = new ObjectMapper();

    public ConceptThemeService(@Qualifier("themeChatModel") ChatModel chatModel,
                                ConceptRepository conceptRepo,
                                ConceptNoteRepository mentionRepo,
                                TopicRepository topicRepo,
                                ConceptTopicRepository edgeRepo,
                                NoteRepository noteRepo,
                                ConceptProperties props,
                                TransactionTemplate tx) {
        this.chatModel = chatModel;
        this.conceptRepo = conceptRepo;
        this.mentionRepo = mentionRepo;
        this.topicRepo = topicRepo;
        this.edgeRepo = edgeRepo;
        this.noteRepo = noteRepo;
        this.props = props;
        this.tx = tx;
    }

    /** 一个概念在分组时能提供的全部信息 */
    private record Item(String id, String name, String desc, String noteTitle) {
    }

    /** 模型给出的一组 */
    private record Group(String name, List<String> members) {
    }

    /**
     * 全量重算：先把新分组算出来，成功后才在一个短事务里原子换表。
     *
     * <p>顺序不能反。曾经是「先删光再调模型」：模型超时/返回垃圾时分组为空，
     * 删除照样提交——一次失败就把用户已有的主题层整个清空，只剩一条 warn 日志。
     * 现在模型调用发生在任何删除之前，失败时旧主题原样保留。
     *
     * <p>也刻意不让事务横跨模型调用：最多 32 批、最坏几分钟，事务里的 H2 写锁
     * 会阻塞期间所有笔记保存。模型在事务外，换表在事务内。
     *
     * <p>成本与概念数成正比（约每 20 个概念一次调用），但只在这些时机发生：
     * 首次生成、模式切换、手动触发。日常新增概念走 {@link #assignPending()}。
     */
    public Map<String, Object> rebuildAll() {
        List<Item> items = loadItems();
        if (items.isEmpty()) {
            return Map.of("topics", 0, "concepts", 0, "mode", "llm");
        }
        List<Group> groups = group(items, List.of());
        if (groups.isEmpty()) {
            log.warn("模型分组没有产出主题，主题层保持原样不动");
            return Map.of("topics", topicRepo.count(), "concepts", items.size(),
                    "mode", "llm", "fallback", true);
        }
        Integer kept = tx.execute(status -> swap(groups, items));
        return Map.of("topics", groups.size(), "concepts", kept != null ? kept : 0, "mode", "llm");
    }

    /**
     * 原子换表：清旧边旧主题、清孤儿概念、写入新分组，一事务完成。
     * 只在分组成功后被调用。
     */
    private int swap(List<Group> groups, List<Item> items) {
        edgeRepo.deleteAllInBatch();
        topicRepo.deleteAllInBatch();
        purgeOrphanConcepts();
        // items 是换表前读的，可能混着刚被清走的孤儿概念；过滤掉，
        // 不给已删除的概念挂悬空边（主题的 conceptCount 也不会被虚增）
        Set<String> alive = new HashSet<>();
        for (Concept c : conceptRepo.findAll()) {
            alive.add(c.getId());
        }
        List<Item> kept = items.stream().filter(i -> alive.contains(i.id())).toList();
        write(groups, kept);
        return kept.size();
    }

    /**
     * 增量归类：只把"还没有归属的概念"交给模型，让它归入已有主题或新建主题。
     *
     * <p>比全量重算便宜一个量级：新增 3 个概念就是 1 次调用，
     * 而全量重算要按概念总数付钱。失败/为空时什么都不写，已有的边和主题不受影响。
     */
    public Map<String, Object> assignPending() {
        List<Item> items = loadItems();
        List<Item> pending = items.stream().filter(i -> !hasEdge(i.id())).toList();
        if (pending.isEmpty()) {
            return Map.of("assigned", 0, "topics", topicRepo.count());
        }
        List<String> existing = topicRepo.findAll().stream()
                .filter(t -> !t.isStructuralNull())
                .map(Topic::getName)
                .toList();
        List<Group> groups = group(pending, existing);
        if (groups.isEmpty()) {
            return Map.of("assigned", 0, "topics", topicRepo.count(), "fallback", true);
        }
        tx.executeWithoutResult(status -> write(groups, pending));
        return Map.of("assigned", pending.size(), "topics", topicRepo.count());
    }

    /** 删掉没有任何提及的概念（重抽后名字变了，旧行会变成孤儿） */
    private void purgeOrphanConcepts() {
        Set<String> used = new HashSet<>();
        for (ConceptNote m : mentionRepo.findAll()) {
            used.add(m.getConceptId());
        }
        int n = 0;
        for (Concept c : conceptRepo.findAll()) {
            if (!used.contains(c.getId())) {
                conceptRepo.delete(c);
                n++;
            }
        }
        if (n > 0) {
            log.info("清理无提及的概念 {} 个", n);
        }
    }

    /** 概念是否已经有主题边 */
    private boolean hasEdge(String conceptId) {
        return !edgeRepo.findByConceptId(conceptId).isEmpty();
    }

    /** 读出全部概念及其说明、来源笔记 */
    private List<Item> loadItems() {
        Map<String, Note> noteById = new HashMap<>();
        for (Note n : noteRepo.findAll()) {
            noteById.put(n.getId(), n);
        }
        Map<String, List<ConceptNote>> byConcept = new LinkedHashMap<>();
        for (ConceptNote m : mentionRepo.findAll()) {
            byConcept.computeIfAbsent(m.getConceptId(), k -> new ArrayList<>()).add(m);
        }
        List<Item> items = new ArrayList<>();
        for (Concept c : conceptRepo.findAll()) {
            List<ConceptNote> ms = byConcept.getOrDefault(c.getId(), List.of());
            String desc = ms.stream()
                    .map(ConceptNote::getDescription)
                    .filter(d -> d != null && !d.isBlank())
                    .max((a, b) -> a.length() - b.length())
                    .orElse("");
            Note src = ms.isEmpty() ? null : noteById.get(ms.get(0).getNoteId());
            items.add(new Item(c.getId(), c.getName(), desc == null ? "" : desc,
                    src == null ? "" : src.getTitle()));
        }
        return items;
    }

    /**
     * 分批送模型，并把结果按主题名合并。
     *
     * <p>每批都把已有主题清单带上去，让模型优先复用——否则每批各起各的名字，
     * 同一个主题会被拆成「检索增强」「RAG 优化」「查询改写」三个。
     */
    private List<Group> group(List<Item> items, List<String> existingNames) {
        int batchSize = Math.max(props.themeBatchSize(), 4);
        Map<String, Group> byName = new LinkedHashMap<>();
        List<Item> todo = new ArrayList<>(items);
        Set<String> placed = new HashSet<>();
        int rounds = 0;

        while (!todo.isEmpty() && rounds < MAX_ROUNDS) {
            rounds++;
            int size = Math.min(batchSize, todo.size());
            List<Item> batch = new ArrayList<>(todo.subList(0, size));
            todo = new ArrayList<>(todo.subList(size, todo.size()));

            Set<String> names = new HashSet<>();
            for (Item i : batch) {
                names.add(i.name());
            }
            StringBuilder sb = new StringBuilder("概念：\n");
            for (Item i : batch) {
                sb.append("- ").append(i.name());
                if (i.desc() != null && !i.desc().isBlank()) {
                    sb.append("（")
                      .append(i.desc().length() > 40 ? i.desc().substring(0, 40) : i.desc())
                      .append("）");
                }
                sb.append('\n');
            }
            if (!byName.isEmpty()) {
                sb.append("\n已有主题（尽量复用）：\n");
                for (String t : byName.keySet()) {
                    sb.append("- ").append(t).append('\n');
                }
            }

            List<Group> raw;
            try {
                raw = parse(chatModel.chat(PROMPT, UserMessage.from(sb.toString())).aiMessage().text());
            } catch (Exception e) {
                log.warn("主题分组调用失败：{}", String.valueOf(e.getMessage()));
                break;
            }

            int placedNow = 0;
            for (Group g : raw) {
                if (g.name() == null || g.name().isBlank() || g.members().isEmpty()) {
                    continue;
                }
                // 后置校验：成员必须是本批真实存在的概念。
                // 模型偶尔把"已有主题名"当成成员吐回来，这类必须挡掉。
                // 顺序要紧：先按上限截断，再标记"已分配"。
                // 反过来的话，被 limit 截掉的成员已经被 marked，
                // 却没写进任何主题——它们既没有归属、也不会再被处理，
                // 覆盖率会凭空掉一截（实测 53/102 对 73/102）。
                List<String> valid = g.members().stream()
                        .map(m -> m == null ? "" : m.trim())
                        .filter(m -> names.contains(m))
                        .distinct()
                        .limit(Math.max(props.themeMaxMembers(), 2))
                        .filter(m -> placed.add(m))
                        .toList();
                if (valid.isEmpty()) {
                    continue;
                }
                List<String> merged = new ArrayList<>(
                        byName.getOrDefault(g.name(), new Group(g.name(), List.of())).members());
                for (String m : valid) {
                    if (!merged.contains(m)) {
                        merged.add(m);
                    }
                }
                byName.put(g.name(), new Group(g.name(), merged));
                placedNow += valid.size();
            }

            if (placedNow == 0) {
                // 这轮一个都没放进去，再问也是同样的结果
                break;
            }
        }
        // 模型没分到的概念留在最后，由 write() 放进「未分类」。
        // 不重试：漏掉一部分是 2B 的常态，重试要多花几轮调用，
        // 而未分类本来就是设计上允许的状态——它标记的是"这里还有没归类好的"。

        List<Group> out = new ArrayList<>();
        for (Group g : byName.values()) {
            if (!g.members().isEmpty()) {
                out.add(g);
            }
        }
        log.info("主题分组：{} 个概念 → {} 个主题，{} 轮，{} 个未分配",
                items.size(), out.size(), rounds, items.size() - placed.size());
        return out;
    }

    /** 解析模型返回的分组 */
    private List<Group> parse(String raw) {
        List<Group> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        try {
            String s = raw.trim();
            if (s.startsWith("```")) {
                int i = s.indexOf('\n');
                int j = s.lastIndexOf("```");
                if (i > 0 && j > i) {
                    s = s.substring(i + 1, j).trim();
                }
            }
            JsonNode root = mapper.readTree(s);
            JsonNode groups = root.isArray() ? root : root.get("groups");
            if (groups == null || !groups.isArray()) {
                return out;
            }
            for (JsonNode g : groups) {
                String name = g.hasNonNull("name") ? g.get("name").asText().trim() : "";
                List<String> members = new ArrayList<>();
                JsonNode ms = g.get("members");
                if (ms != null && ms.isArray()) {
                    for (JsonNode m : ms) {
                        if (m.isTextual()) {
                            members.add(m.asText().trim());
                        }
                    }
                }
                if (!name.isEmpty() && !members.isEmpty()) {
                    out.add(new Group(name, members));
                }
            }
        } catch (Exception e) {
            log.warn("主题分组返回解析失败：{}", String.valueOf(e.getMessage()));
        }
        return out;
    }

    /** 把分组结果写进主题层 */
    private void write(List<Group> groups, List<Item> items) {
        Map<String, Topic> topics = new LinkedHashMap<>();
        Topic loose = newTopic("未分类", "folder", true);
        topics.put("未分类", loose);

        Map<String, Item> byName = new HashMap<>();
        for (Item i : items) {
            byName.put(i.name(), i);
        }

        Map<String, Integer> memberCount = new HashMap<>();
        int assigned = 0;
        for (Group g : groups) {
            // 只有一个成员的主题等于没分组——它没有"共同主题"可言，
            // 留在页面上只是一个装概念的盒子，交给未分类更诚实
            if (g.members().size() < 2) {
                continue;
            }
            Topic t = topics.computeIfAbsent(g.name(), k -> newTopic(k, "llm", false));
            for (String member : g.members()) {
                Item item = byName.get(member);
                if (item == null) {
                    continue;
                }
                addEdge(item.id(), t, 1.0, true);
                memberCount.merge(t.getId(), 1, Integer::sum);
                assigned++;
            }
        }        // 模型没分到的概念进兜底桶。
        // 不按文件夹兜底：文件夹是文件组织，不是主题依据——
        // 用它给漏网概念找归属，等于把"分错的主题"藏进看起来正确的分组里。
        Set<String> used = new HashSet<>();
        for (Group g : groups) {
            if (g.members().size() >= 2) {
                used.addAll(g.members());
            }
        }
        for (Item i : items) {
            if (!used.contains(i.name())) {
                addEdge(i.id(), loose, 1.0, true);
                memberCount.merge(loose.getId(), 1, Integer::sum);
                assigned++;
            }
        }
        for (Topic t : topics.values()) {
            t.setConceptCount(memberCount.getOrDefault(t.getId(), 0));
            t.setRuleVersion(TOPIC_RULE_VERSION);
            topicRepo.save(t);
        }
        log.info("主题重建完成：{} 个主题，覆盖 {}/{} 个概念（模式 llm）",
                topics.size() - 1, assigned, items.size());
    }

    private Topic newTopic(String name, String source, boolean structuralNull) {
        Topic t = new Topic();
        t.setName(name);
        t.setSource(source);
        t.setStructuralNull(structuralNull);
        return topicRepo.save(t);
    }

    private void addEdge(String conceptId, Topic t, double weight, boolean primary) {
        ConceptTopic edge = new ConceptTopic();
        edge.setConceptId(conceptId);
        edge.setTopicId(t.getId());
        edge.setWeight(weight);
        edge.setPrimary(primary);
        edgeRepo.save(edge);
    }

    /** 主题生成规则的版本，与 ConceptService 共用同一套版本号语义 */
    public static final int TOPIC_RULE_VERSION = 2;
}
