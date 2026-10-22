package cn.gudqs7.plugins.search;

import cn.gudqs7.plugins.common.enums.HttpMethod;
import cn.gudqs7.plugins.common.util.jetbrain.ExceptionUtil;
import cn.gudqs7.plugins.search.icon.IconHolder;
import cn.gudqs7.plugins.search.resolver.ApiNavigationItem;
import cn.gudqs7.plugins.search.resolver.ApiResolverService;
import com.intellij.ide.actions.SearchEverywherePsiRenderer;
import com.intellij.ide.actions.bigPopup.ShowFilterAction;
import com.intellij.ide.actions.searcheverywhere.FoundItemDescriptor;
import com.intellij.ide.actions.searcheverywhere.PersistentSearchEverywhereContributorFilter;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager;
import com.intellij.ide.actions.searcheverywhere.WeightedSearchEverywhereContributor;
import com.intellij.ide.util.ElementsChooser;
import com.intellij.idea.ActionsBundle;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.codeStyle.MinusculeMatcher;
import com.intellij.psi.codeStyle.NameUtil;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.speedSearch.SpeedSearchUtil;
import com.intellij.util.Processor;
import com.intellij.util.PsiNavigateUtil;
import com.intellij.util.ui.UIUtil;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.*;

/**
 * @author wq
 * @date 2022/5/28
 */
public class ApiSearchContributor implements WeightedSearchEverywhereContributor<ApiNavigationItem> {

    /**
     * 命中 url 的结果排在只命中方法描述的结果之前
     */
    private static final int URL_MATCH_BONUS = 1000;

    private static final Comparator<FoundItemDescriptor<ApiNavigationItem>> BY_WEIGHT_DESC =
            Comparator.<FoundItemDescriptor<ApiNavigationItem>>comparingInt(FoundItemDescriptor::getWeight).reversed();

    private final AnActionEvent actionEvent;
    private final Project myProject;
    private PersistentSearchEverywhereContributorFilter<HttpMethod> myFilter;

    public ApiSearchContributor(@NotNull AnActionEvent event) {
        this.actionEvent = event;
        myProject = event.getRequiredData(CommonDataKeys.PROJECT);
        MethodFilterConfiguration methodFilterConfiguration = MethodFilterConfiguration.getInstance(myProject);
        if (methodFilterConfiguration != null) {
            myFilter = new PersistentSearchEverywhereContributorFilter<>(
                    Arrays.asList(HttpMethod.values()), methodFilterConfiguration,
                    Enum::name, httpMethod -> null
            );
        }
    }

    @NotNull
    @Override
    public String getSearchProviderId() {
        return getClass().getSimpleName();
    }

    @NotNull
    @Override
    public String getGroupName() {
        return "Api";
    }

    @Override
    public int getSortWeight() {
        return 800;
    }

    @Nullable
    @Override
    public String getAdvertisement() {
        return DumbService.isDumb(myProject) ? "Results might be incomplete. The project is being indexed." : "type url or to search";
    }

    @Override
    public boolean processSelectedItem(@NotNull ApiNavigationItem selected, int modifiers, @NotNull String searchText) {
        // 来自磁盘索引的条目需要按文件偏移恢复 PSI, 故先取元素再导航
        PsiElement psiElement = ApplicationManager.getApplication().runReadAction(
                (Computable<PsiElement>) selected::getPsiElement);
        if (psiElement == null) {
            return false;
        }
        PsiNavigateUtil.navigate(psiElement);
        return true;
    }

    @NotNull
    @Override
    public ListCellRenderer<Object> getElementsRenderer() {
        return new SearchEverywherePsiRenderer(this) {

            @Override
            protected boolean customizeNonPsiElementLeftRenderer(ColoredListCellRenderer renderer, JList list, Object value, int index, boolean selected, boolean hasFocus) {
                try {
                    Color fgColor = list.getForeground();
                    Color bgColor = UIUtil.getListBackground();
                    SimpleTextAttributes nameAttributes = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, fgColor);

                    ItemMatchers itemMatchers = getItemMatchers(list, value);
                    ApiNavigationItem apiNavigationItem = (ApiNavigationItem) value;
                    String name = apiNavigationItem.getUrl();
                    String locationString = " " + apiNavigationItem.getRightText();

                    SpeedSearchUtil.appendColoredFragmentForMatcher(name, renderer, nameAttributes, itemMatchers.nameMatcher, bgColor, selected);
                    renderer.setIcon(IconHolder.getHttpMethodIcon(apiNavigationItem.getHttpMethod()));

                    if (StringUtils.isNotEmpty(locationString)) {
                        FontMetrics fm = list.getFontMetrics(list.getFont());
                        int maxWidth = list.getWidth() - fm.stringWidth(name) - myRightComponentWidth - 36;
                        int fullWidth = fm.stringWidth(locationString);
                        if (fullWidth < maxWidth) {
                            SpeedSearchUtil.appendColoredFragmentForMatcher(locationString, renderer, SimpleTextAttributes.GRAYED_ATTRIBUTES, itemMatchers.nameMatcher, bgColor, selected);
                        } else {
                            int adjustedWidth = Math.max(locationString.length() * maxWidth / fullWidth - 1, 3);
                            locationString = StringUtil.trimMiddle(locationString, adjustedWidth);
                            SpeedSearchUtil.appendColoredFragmentForMatcher(locationString, renderer, SimpleTextAttributes.GRAYED_ATTRIBUTES, itemMatchers.nameMatcher, bgColor, selected);
                        }
                    }
                    return true;
                } catch (Throwable ex) {
                    ExceptionUtil.handleException(ex);
                    return false;
                }
            }
        };
    }

    @Nullable
    @Override
    public Object getDataForItem(@NotNull ApiNavigationItem element, @NotNull String dataId) {
        return null;
    }

    @Override
    public boolean isEmptyPatternSupported() {
        return true;
    }

    @Override
    public boolean isShownInSeparateTab() {
        return true;
    }

    @Override
    public boolean showInFindResults() {
        return false;
    }

    @Override
    public boolean isDumbAware() {
        return DumbService.isDumb(myProject);
    }

    @Override
    public void fetchWeightedElements(@NotNull String pattern, @NotNull ProgressIndicator progressIndicator, @NotNull Processor<? super FoundItemDescriptor<ApiNavigationItem>> consumer) {
        try {
            if (isDumbAware() || !shouldProvideElements(pattern)) {
                return;
            }

            // 结果由 ApiResolverService 缓存(内存 + 磁盘), 命中时不需要重新解析 PSI
            List<ApiNavigationItem> navItemList = ApiResolverService.getInstance(myProject)
                    .getApiNavigationItemList(progressIndicator);
            if (navItemList.isEmpty()) {
                return;
            }

            MinusculeMatcher matcher = NameUtil.buildMatcher("*" + pattern + "*", NameUtil.MatchingCaseSensitivity.NONE);
            Set<HttpMethod> httpMethodSet = new HashSet<>();
            if (myFilter == null) {
                httpMethodSet.addAll(Arrays.asList(HttpMethod.values()));
            } else {
                httpMethodSet.addAll(myFilter.getSelectedElements());
            }
            boolean selectAll = httpMethodSet.size() == HttpMethod.values().length;

            List<FoundItemDescriptor<ApiNavigationItem>> foundItems = new ArrayList<>();
            for (ApiNavigationItem restItem : navItemList) {
                // 用户继续输入时尽快退出, 不再跑完整个列表
                progressIndicator.checkCanceled();
                if (!selectAll && !httpMethodSet.contains(restItem.getHttpMethod())) {
                    continue;
                }
                int weight = getMatchWeight(matcher, restItem);
                if (weight > 0) {
                    foundItems.add(new FoundItemDescriptor<>(restItem, weight));
                }
            }
            foundItems.sort(BY_WEIGHT_DESC);
            for (FoundItemDescriptor<ApiNavigationItem> foundItem : foundItems) {
                if (!consumer.process(foundItem)) {
                    return;
                }
            }
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (Throwable ex) {
            ExceptionUtil.handleException(ex);
        }
    }

    private static int getMatchWeight(@NotNull MinusculeMatcher matcher, @NotNull ApiNavigationItem item) {
        String url = item.getUrl();
        if (matcher.matches(url)) {
            return Math.max(matcher.matchingDegree(url), 1) + URL_MATCH_BONUS;
        }
        String methodDesc = item.getMethodPathInfo().getMethodDesc();
        if (StringUtil.isNotEmpty(methodDesc) && matcher.matches(methodDesc)) {
            return Math.max(matcher.matchingDegree(methodDesc), 1);
        }
        return -1;
    }

    @Override
    public @NotNull List<AnAction> getActions(@NotNull Runnable onChanged) {
        // 获取 Filter 的 action
        if (myProject == null || myFilter == null) {
            return Collections.emptyList();
        }
        ArrayList<AnAction> result = new ArrayList<>();
        result.add(new FiltersAction(myFilter, onChanged));
        return result;
    }

    /**
     * 判断是否应该返回列表元素
     *
     * @param pattern 搜索词
     */
    private boolean shouldProvideElements(String pattern) {
        if (StringUtils.isNotBlank(pattern)) {
            return true;
        }
        SearchEverywhereManager seManager = SearchEverywhereManager.getInstance(myProject);
        if (seManager.isShown()) {
            // 非 All Tab
            return getSearchProviderId().equals(seManager.getSelectedTabID());
        } else {
            // ALL Tab
            return !ActionsBundle.message("action.SearchEverywhere.text").equals(actionEvent.getPresentation().getText());
        }
    }

    static class FiltersAction extends ShowFilterAction {

        final PersistentSearchEverywhereContributorFilter<?> filter;
        final Runnable rebuildRunnable;

        FiltersAction(@NotNull PersistentSearchEverywhereContributorFilter<?> filter, @NotNull Runnable rebuildRunnable) {
            this.filter = filter;
            this.rebuildRunnable = rebuildRunnable;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        protected boolean isActive() {
            return filter.getAllElements().size() != filter.getSelectedElements().size();
        }

        @Override
        protected ElementsChooser<?> createChooser() {
            return createChooser(filter, rebuildRunnable);
        }

        private static <T> ElementsChooser<T> createChooser(@NotNull PersistentSearchEverywhereContributorFilter<T> filter, @NotNull Runnable rebuildRunnable) {
            ElementsChooser<T> res = new ElementsChooser<T>(filter.getAllElements(), false) {
                @Override
                protected String getItemText(@NotNull T value) {
                    return filter.getElementText(value);
                }

                @Nullable
                @Override
                protected Icon getItemIcon(@NotNull T value) {
                    return filter.getElementIcon(value);
                }
            };
            res.markElements(filter.getSelectedElements());
            ElementsChooser.ElementsMarkListener<T> listener = (element, isMarked) -> {
                filter.setSelected(element, isMarked);
                rebuildRunnable.run();
            };
            res.addElementsMarkListener(listener);
            return res;
        }

    }
}
