package cn.gudqs7.plugins.search.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurableProvider;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * 设置页的构造入口, 通过 project 注入以便复用当前项目的索引服务
 *
 * @author wq
 */
public class ApiSaviorConfigurableProvider extends ConfigurableProvider {

    private final Project project;

    public ApiSaviorConfigurableProvider(Project project) {
        this.project = project;
    }

    @NotNull
    @Override
    public Configurable createConfigurable() {
        return new ApiSaviorSettingsConfigurable(project);
    }
}
