package dev.local.docinspector.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 老板键全局 Action（默认快捷键 {@code Alt+Q}），触发当前工程工作区遮罩切换。
 */
public class BossKeyAction extends AnAction implements DumbAware {

    public interface BossMaskHost {
        void toggleBossMask();
    }

    private static final Map<Project, BossMaskHost> PROJECT_HOSTS = new ConcurrentHashMap<>();
    private static volatile BossMaskHost lastActiveHost;

    public static void registerHost(Project project, BossMaskHost host) {
        if (host == null) {
            return;
        }
        if (project != null) {
            PROJECT_HOSTS.put(project, host);
        }
        lastActiveHost = host;
    }

    public static void unregisterHost(Project project, BossMaskHost host) {
        if (project != null) {
            PROJECT_HOSTS.remove(project, host);
        }
        if (lastActiveHost == host) {
            lastActiveHost = PROJECT_HOSTS.values().stream().findFirst().orElse(null);
        }
    }

    public static boolean toggleForProject(Project project) {
        BossMaskHost host = project != null ? PROJECT_HOSTS.get(project) : null;
        if (host == null) {
            host = lastActiveHost;
        }
        if (host != null) {
            host.toggleBossMask();
            return true;
        }
        return false;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        toggleForProject(e.getProject());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        boolean available = (project != null && PROJECT_HOSTS.containsKey(project)) || lastActiveHost != null;
        e.getPresentation().setEnabled(available);
    }
}
