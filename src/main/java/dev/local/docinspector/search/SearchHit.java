package dev.local.docinspector.search;

/**
 * 全文检索单条命中结果。
 *
 * @param unitIndex          所在单元索引（0-based）
 * @param matchOrdinalInUnit 该单元内第几次匹配（0-based）
 * @param locationLabel      可读定位标签（如 {@code Ch.3 · 概述} 或 {@code P.12}）
 * @param beforeText         匹配词前约 30 字上下文摘要
 * @param matchedText        实际命中的文本片段（保留原文大小写）
 * @param afterText          匹配词后约 30 字上下文摘要
 */
public record SearchHit(
        int unitIndex,
        int matchOrdinalInUnit,
        String locationLabel,
        String beforeText,
        String matchedText,
        String afterText
) {
    public SearchHit {
        locationLabel = locationLabel != null ? locationLabel : "";
        beforeText = beforeText != null ? beforeText : "";
        matchedText = matchedText != null ? matchedText : "";
        afterText = afterText != null ? afterText : "";
    }
}
