package dev.local.docinspector.statusbar;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import com.intellij.util.Consumer;
import dev.local.docinspector.state.DocInspectorStateService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.event.MouseEvent;

/**
 * 底部状态栏右侧微读小部件工厂（替换 T1 壳）。
 * 默认隐藏；开启后展示视口首句，左键下一句、右键上一句、中键瞬间清空隐藏。
 */
public class MicroReadWidgetFactory implements StatusBarWidgetFactory {

    public static final String ID = "DocInspectorMicroBar";

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Document Status Bar";
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new MicroReadStatusBarWidget();
    }

    private static final class MicroReadStatusBarWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {

        private final MicroReadBroadcaster broadcaster = MicroReadBroadcaster.getInstance();
        private volatile StatusBar statusBar;
        private final Runnable changeListener = this::refreshWidget;
        private final Runnable hideListener = () -> {
            try {
                DocInspectorStateService.getInstance().updateSettings(s -> s.setMicroBarEnabled(false));
            } catch (Throwable ignored) {
            }
        };

        @Override
        public @NotNull String ID() {
            return ID;
        }

        @Override
        public @NotNull WidgetPresentation getPresentation() {
            return this;
        }

        @Override
        public void install(@NotNull StatusBar statusBar) {
            this.statusBar = statusBar;
            try {
                broadcaster.setEnabled(DocInspectorStateService.getInstance().getSettings().isMicroBarEnabled());
            } catch (Throwable ignored) {
            }
            broadcaster.addChangeListener(changeListener);
            broadcaster.addHideListener(hideListener);
            refreshWidget();
        }

        @Override
        public void dispose() {
            broadcaster.removeChangeListener(changeListener);
            broadcaster.removeHideListener(hideListener);
            this.statusBar = null;
        }

        @Override
        public @NotNull String getText() {
            return broadcaster.getCurrentDisplayText();
        }

        @Override
        public float getAlignment() {
            return Component.LEFT_ALIGNMENT;
        }

        @Override
        public @Nullable String getTooltipText() {
            String text = broadcaster.getCurrentDisplayText();
            if (text.isEmpty()) {
                return null;
            }
            return "Reference Status (Left: Next, Right: Prev, Middle: Hide)";
        }

        @Override
        public @Nullable Consumer<MouseEvent> getClickConsumer() {
            return e -> {
                if (e == null) {
                    return;
                }
                if (SwingUtilities.isMiddleMouseButton(e)) {
                    broadcaster.hideImmediately();
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    broadcaster.prevSentence();
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    broadcaster.nextSentence();
                }
            };
        }

        private void refreshWidget() {
            StatusBar bar = this.statusBar;
            if (bar == null) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                StatusBar b = this.statusBar;
                if (b != null) {
                    b.updateWidget(ID);
                }
            });
        }
    }
}
