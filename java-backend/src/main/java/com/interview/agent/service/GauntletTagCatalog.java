package com.interview.agent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 八股拷打预置标签树：二级标签 -> 考察主题与 Skill 映射。
 *
 * 标签是面向前端的稳定 ID；topic 用于计划/轮转展示，skill 用于复用 Skills 上下文、
 * 题库检索与评测技能标签。新增标签只需在此追加，无需改 Agent 核心逻辑。
 */
public final class GauntletTagCatalog {

    public record Tag(String id, String label, String topic, String skill) {
    }

    public record Group(String id, String label, List<Tag> children) {
    }

    public static final List<Group> GROUPS = List.of(
            new Group("java", "JAVA", List.of(
                    new Tag("java-collection", "JAVA集合", "JAVA集合", "java"),
                    new Tag("java-concurrency", "并发编程", "Java并发编程", "java"),
                    new Tag("java-jvm", "JVM", "JVM", "java"),
                    new Tag("java-spring", "Spring", "Spring", "java"))),
            new Group("mysql", "MySQL", List.of(
                    new Tag("mysql-index", "索引与执行计划", "MySQL索引与执行计划", "mysql"),
                    new Tag("mysql-tx", "事务与锁", "MySQL事务与锁", "mysql"),
                    new Tag("mysql-tuning", "性能调优", "MySQL性能调优", "mysql"))),
            new Group("redis", "Redis", List.of(
                    new Tag("redis-struct", "数据结构与底层编码", "Redis数据结构与底层编码", "redis"),
                    new Tag("redis-persist", "持久化与高可用", "Redis持久化与高可用", "redis"),
                    new Tag("redis-cache", "缓存问题与分布式锁", "Redis缓存问题与分布式锁", "redis"))),
            new Group("system-design", "系统设计", List.of(
                    new Tag("sd-distributed", "分布式基础", "分布式基础", "system-design"),
                    new Tag("sd-highconcurrency", "高并发设计", "高并发设计", "system-design"),
                    new Tag("sd-mq", "消息队列", "消息队列", "mq"))),
            new Group("cs", "计算机基础", List.of(
                    new Tag("cs-network", "计算机网络", "计算机网络", "network"),
                    new Tag("cs-os", "操作系统", "操作系统", "os")))
    );

    private static final Map<String, Tag> INDEX = buildIndex();

    private GauntletTagCatalog() {
    }

    private static Map<String, Tag> buildIndex() {
        Map<String, Tag> index = new LinkedHashMap<>();
        for (Group group : GROUPS) {
            for (Tag tag : group.children()) {
                index.put(tag.id(), tag);
            }
        }
        return index;
    }

    /** 按请求顺序去重并校验标签；未知标签直接拒绝，避免任意 topic/skill 注入。 */
    public static List<Tag> resolve(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个八股标签");
        }
        List<Tag> tags = new ArrayList<>();
        for (String id : new LinkedHashSet<>(ids)) {
            Tag tag = INDEX.get(id);
            if (tag == null) {
                throw new IllegalArgumentException("未知的八股标签: " + id);
            }
            tags.add(tag);
        }
        if (tags.size() > 6) {
            throw new IllegalArgumentException("最多选择6个八股标签");
        }
        return tags;
    }

    /** 扁平清单（R-10）：供 AI 层生成训练计划时按 id 引用标签，[{id,label,skill}]。 */
    public static List<Map<String, Object>> flatten() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tag tag : INDEX.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", tag.id());
            m.put("label", tag.label());
            m.put("skill", tag.skill());
            out.add(m);
        }
        return out;
    }
}
