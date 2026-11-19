package dev.local.docinspector.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.components.JBScrollPane;

import javax.swing.BorderFactory;
import javax.swing.JScrollPane;
import java.awt.Component;

/**
 * 统一创建无亮白 3D 边框、在 IDEA 内走原生暗色半透明滚动条的 Swing 容器辅助类。
 */
public final class UiChrome {

    private UiChrome() {
    }

    public static JScrollPane wrapScrollPane(Component view) {
        JScrollPane scrollPane;
        if (ApplicationManager.getApplication() != null) {
            try {
                scrollPane = new JBScrollPane(view);
            } catch (Throwable ignored) {
                scrollPane = new JScrollPane(view);
            }
        } else {
            scrollPane = new JScrollPane(view);
        }
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setViewportBorder(BorderFactory.createEmptyBorder());
        return scrollPane;
    }
}
