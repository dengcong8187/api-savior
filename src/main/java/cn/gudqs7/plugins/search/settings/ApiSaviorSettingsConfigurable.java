package cn.gudqs7.plugins.search.settings;

import cn.gudqs7.plugins.search.resolver.ApiResolverService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.FlowLayout;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 设置页: 展示接口索引状态, 并提供重建与清理入口
 *
 * @author wq
 */
public class ApiSaviorSettingsConfigurable implements SearchableConfigurable {

    private static final String ID = "cn.gudqs.api.savior.settings";

    private final Project project;

    private JPanel mainPanel;
    private JBLabel indexPathLabel;
    private JBLabel fileStateLabel;
    private JBLabel cacheStateLabel;
    private JButton rebuildButton;

    public ApiSaviorSettingsConfigurable(Project project) {
        this.project = project;
    }

    @NotNull
    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Api Savior";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        indexPathLabel = new JBLabel();
        fileStateLabel = new JBLabel();
        cacheStateLabel = new JBLabel();
        rebuildButton = new JButton("重建索引");
        JButton clearButton = new JButton("清理索引缓存");

        rebuildButton.addActionListener(e -> rebuildIndex());
        clearButton.addActionListener(e -> clearIndex());

        JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        buttonsPanel.setOpaque(false);
        buttonsPanel.add(rebuildButton);
        buttonsPanel.add(clearButton);

        mainPanel = FormBuilder.createFormBuilder()
                .addComponent(new JBLabel("<html><b>接口索引</b></html>"))
                .addComponent(new JBLabel("<html>索引用于「双击 Shift 后切到 Api 页」的接口搜索。"
                        + "结果会缓存在 IDE 的缓存目录, 源文件变化时只重建对应的文件。"
                        + "搜索结果缺失或网址过旧时可在此重建, 不会影响 IDEA 自身的索引。</html>"))
                .addLabeledComponent(new JBLabel("索引文件"), indexPathLabel)
                .addLabeledComponent(new JBLabel("文件状态"), fileStateLabel)
                .addLabeledComponent(new JBLabel("内存缓存"), cacheStateLabel)
                .addComponent(buttonsPanel)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        refreshState();
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public void apply() {
        // 本页只有动作按钮, 没有需要保存的设置
    }

    @Override
    public void reset() {
        refreshState();
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        indexPathLabel = null;
        fileStateLabel = null;
        cacheStateLabel = null;
        rebuildButton = null;
    }

    private void rebuildIndex() {
        if (DumbService.isDumb(project)) {
            Messages.showInfoMessage(project, "项目正在建立索引, 请完成后再试。", "Api Savior");
            return;
        }
        rebuildButton.setEnabled(false);
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "重建接口索引", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                ApiResolverService.getInstance(project).rebuildNow(indicator);
            }

            @Override
            public void onFinished() {
                if (rebuildButton != null) {
                    rebuildButton.setEnabled(true);
                }
                refreshState();
            }
        });
    }

    private void clearIndex() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            ApiResolverService.getInstance(project).invalidateCache();
            ApplicationManager.getApplication().invokeLater(this::refreshState);
        });
    }

    /**
     * 状态读取涉及文件属性, 放到后台线程, 结果回到 EDT 更新
     */
    private void refreshState() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            ApiResolverService.IndexFileInfo info = ApiResolverService.getInstance(project).getIndexFileInfo();
            ApplicationManager.getApplication().invokeLater(() -> applyState(info));
        });
    }

    private void applyState(@NotNull ApiResolverService.IndexFileInfo info) {
        if (mainPanel == null) {
            return;
        }
        String filePath = info.getFilePath();
        indexPathLabel.setText(filePath == null ? "不可用(项目路径未知)" : filePath);
        if (info.isFileExists()) {
            fileStateLabel.setText(String.format("%s, 最后写入于 %s", formatSize(info.getFileSize()),
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(info.getLastModified()))));
        } else {
            fileStateLabel.setText("尚未生成, 首次搜索接口时自动创建");
        }
        if (info.getCachedItemCount() >= 0) {
            cacheStateLabel.setText(String.format("%d 个接口 / %d 个源文件",
                    info.getCachedItemCount(), Math.max(info.getCachedFileCount(), 0)));
        } else {
            cacheStateLabel.setText("本次会话尚未构建");
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
