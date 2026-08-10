package com.chisel.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 下一步建议容器（Claude Code 式）：Agent 完成任务后异步生成 2-3 条
 * 「接下来可以做什么」的候选指令，供 ChiselCompleter 在空输入时 Tab 补全。
 *
 * 线程模型：Agent 后台线程写入（generate→set），Main 交互线程读取（suggestions()），
 * UI 更新用 listener 回调（保证不跨线程直接操作终端）。
 */
public final class NextStepSuggestions {

    private final List<String> suggestions = new CopyOnWriteArrayList<>();
    private volatile Consumer<List<String>> listener;

    /** 替换当前建议集（Agent 每轮完成后调用）。 */
    public void set(List<String> next) {
        suggestions.clear();
        if (next != null) {
            for (String s : next) {
                if (s != null && !s.isBlank()) {
                    suggestions.add(s.trim());
                }
            }
        }
        Consumer<List<String>> l = listener;
        if (l != null) {
            l.accept(current());
        }
    }

    /** 当前建议快照（供 completer 读取）。 */
    public List<String> current() {
        return List.copyOf(suggestions);
    }

    public boolean isEmpty() {
        return suggestions.isEmpty();
    }

    /** 注册建议变更回调（Main 用它更新 UI 提示）。 */
    public void setListener(Consumer<List<String>> listener) {
        this.listener = listener;
    }

    /** 清空（/clear、新任务开始前）。 */
    public void clear() {
        suggestions.clear();
    }

    /** 组装成 UI 提示文本；无建议返回空串。 */
    public static String formatHint(List<String> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("💡 下一步（按 Tab 补全）: ");
        for (int i = 0; i < suggestions.size(); i++) {
            if (i > 0) {
                sb.append("  ");
            }
            sb.append(i + 1).append(". ").append(suggestions.get(i));
        }
        return sb.toString();
    }

    /** 空输入时作为 Tab 候选：完全匹配的建议保留，避免建议污染正常输入补全。 */
    public List<String> tabCandidates() {
        return new ArrayList<>(suggestions);
    }
}
