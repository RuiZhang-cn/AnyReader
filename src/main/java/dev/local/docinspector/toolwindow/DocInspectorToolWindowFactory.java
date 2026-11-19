package dev.local.docinspector.toolwindow;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Docs 工具窗入口（替换 T1 壳）：装配 {@link MainWorkspacePanel} 并绑定 {@link Disposer} 生命周期。
 */
public class DocInspectorToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        MainWorkspacePanel workspace = new MainWorkspacePanel(project);
        Content content = ContentFactory.getInstance().createContent(workspace, "", false);
        content.setDisposer(workspace);
        Disposer.register(toolWindow.getDisposable(), workspace);
        toolWindow.getContentManager().addContent(content);
    }
}
