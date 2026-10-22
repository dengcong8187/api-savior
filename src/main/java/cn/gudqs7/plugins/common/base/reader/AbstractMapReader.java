package cn.gudqs7.plugins.common.base.reader;

import cn.gudqs7.plugins.common.enums.FieldType;
import cn.gudqs7.plugins.common.enums.StructureType;
import cn.gudqs7.plugins.common.pojo.ReadOnlyMap;
import cn.gudqs7.plugins.common.pojo.resolver.CommentInfo;
import cn.gudqs7.plugins.common.pojo.resolver.StructureAndCommentInfo;
import cn.gudqs7.plugins.common.util.jetbrain.ExceptionUtil;
import cn.gudqs7.plugins.common.util.structure.BaseTypeUtil;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.psi.PsiType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * @author WQ
 * @date 2022/4/4
 */
public abstract class AbstractMapReader<B> extends AbstractReader<Map<String, Object>, B> implements IStructureAndCommentReader<B> {

    @Override
    protected void beforeRead(StructureAndCommentInfo structureAndCommentInfo, Map<String, Object> data) {
        data.put("map", new LinkedHashMap<>(32));
    }

    @Override
    protected B afterRead(StructureAndCommentInfo structureAndCommentInfo, Map<String, Object> data) {
        return getFromData(data, "map");
    }

    @Override
    protected void beforeLoop(StructureAndCommentInfo structureAndCommentInfo, Map<String, Object> loopData, Map<String, Object> data, ReadOnlyMap parentData) {
        LinkedHashMap<Object, Object> loopObj = new LinkedHashMap<>(32);
        loopData.put("loopObj", loopObj);
    }

    @Override
    protected void beforeLoop0(StructureAndCommentInfo structureAndCommentInfo, StructureAndCommentInfo parentStructureAndCommentInfo, Map<String, Object> data, ReadOnlyMap parentData) {

    }

    @Override
    protected void inLoop(StructureAndCommentInfo structureAndCommentInfo, Map<String, Object> leafData, Map<String, Object> loopData, Map<String, Object> data, ReadOnlyMap parentData) {
        if (structureAndCommentInfo != null && structureAndCommentInfo.isLeaf()) {
            Boolean onlyRequire = (Boolean) data.get("onlyRequire");
            if (onlyRequire != null && onlyRequire) {
                // 要求必须必填才放入
                boolean required = structureAndCommentInfo.getCommentInfo().isRequired(false);
                if (!required) {
                    return;
                }
            }
        }
        Map<String, Object> loopObj = getFromData(loopData, "loopObj");
        loopObj.putAll(leafData);
    }

    @Override
    protected void afterLoop(StructureAndCommentInfo structureAndCommentInfo, Map<String, Object> data, ReadOnlyMap parentData, Map<String, Object> loopData, Map<String, Object> leafData, boolean leaf) {
        Map<String, Object> map = getFromData(data, "map");
        Map<String, Object> loopObj = getFromData(loopData, "loopObj");
        String fieldName = structureAndCommentInfo.getFieldName();
        Integer type = structureAndCommentInfo.getType();

        boolean needBreakUpParam = false;
        if (StructureType.PSI_PARAM.getType().equals(type)) {
            needBreakUpParam = needBreakUpParam(structureAndCommentInfo, map, fieldName);
        }

        if (leaf) {
            // 处理只有一个节点且为普通类型时的情况(即 read0 中 leaf=true 分支中的 afterLoop 进入时)
            map.putAll(loopObj);
            return;
        } else {
            // leafData 代表循环外的节点数据, 可称为父节点, 一般包含一对 key/value; 节点名称为 key, value 一般为 null 或预设值(参考 readLeaf中 switch)
            // loopObj 代表父节点的 children 所有数据, 一般为多对 key/value, 子节点名称为 key, value 可能是普通类型, 也可能是 Map/List (取决于 子节点是否还有子节点)
            // 这里判断是否需要覆盖父节点 (leafData) 的数据(因为 readLeaf 时不会直接返回数据)
            if (fieldName != null) {
                if (leafData.containsKey(fieldName)) {
                    List<Integer> containerTypeCodes = structureAndCommentInfo.getContainerTypeCodes();
                    if (containerTypeCodes != null && !containerTypeCodes.isEmpty()) {
                        // 容器(如 Map/List)包裹对象时, 需按容器层级重新包裹, 对象数据放入最内层
                        leafData.put(fieldName, buildContainerVal(containerTypeCodes, 0, () -> loopObj));
                    } else if (needBreakUpParam) {
                        leafData.remove(fieldName);
                        leafData.putAll(loopObj);
                    } else {
                        leafData.put(fieldName, loopObj);
                    }
                } else {
                    map.put(fieldName, loopObj);
                }
            } else {
                // 没有 fieldName, 没法 put 单个, 只好 putAll
                map.putAll(loopObj);
            }
        }

        // 单个参数/单个返回值处理
        if (structureAndCommentInfo.getParent() == null) {
            // 当单个返回值为pojo时, leafData 数据赋值成功, 但 map 仍为空, 需要添加到 map 中
            if (StructureType.of(type) == StructureType.PSI_RETURN) {
                map.putAll(leafData);
            }
        }
    }

    /**
     * 若方法参数类型是pojo, 是否拆分 pojo 的字段到 map 中
     * 主要在于 Spring MVC 绑定方法参数时, 忽视方法参数的前缀, 因此需要拆分
     * 另: 可在此方法中进行一些与方法参数有关的处理
     *
     * @param structureAndCommentInfo 结构信息(含注释)
     * @param map                     最终 map
     * @param fieldName               方法参数变量名
     * @return true: 拆分 | false:不拆分
     */
    protected abstract boolean needBreakUpParam(StructureAndCommentInfo structureAndCommentInfo, Map<String, Object> map, String fieldName);

    @Override
    protected Map<String, Object> readLeaf(StructureAndCommentInfo structureAndCommentInfo, Map<String, Object> data, ReadOnlyMap parentData) {
        String fieldName = structureAndCommentInfo.getFieldName();
        if (fieldName != null) {
            Map<String, Object> map = new LinkedHashMap<>();
            // 非叶子节点(如容器内为用户自定义类)的内层值, 之后会在 afterLoop 中覆盖, 故直接放 null
            Supplier<Object> innerVal = () -> structureAndCommentInfo.isLeaf() ? getJsonMapVal(structureAndCommentInfo, data, parentData) : null;
            map.put(fieldName, buildContainerVal(structureAndCommentInfo.getContainerTypeCodes(), 0, innerVal));
            return map;
        }
        return null;
    }

    /**
     * 按容器嵌套层级(由外向内)逐层包裹字段值
     * 如 Map&lt;String, List&lt;String&gt;&gt; 的层级为 [Map, List], 结果为 {"key1":["val","val"],"key2":["val","val"]}
     *
     * @param containerTypeCodes 由外至内的容器类型
     * @param index              当前层级
     * @param innerVal           最内层的值(每个元素独立获取, 以保证示例值互不相同)
     * @return 包裹后的值
     */
    private Object buildContainerVal(List<Integer> containerTypeCodes, int index, Supplier<Object> innerVal) {
        if (containerTypeCodes == null || index >= containerTypeCodes.size()) {
            return innerVal.get();
        }
        switch (FieldType.of(containerTypeCodes.get(index))) {
            case MAP:
                Map<String, Object> valMap = new LinkedHashMap<>(4);
                valMap.put("key1", buildContainerVal(containerTypeCodes, index + 1, innerVal));
                valMap.put("key2", buildContainerVal(containerTypeCodes, index + 1, innerVal));
                return valMap;
            case ARRAY:
            case LIST:
            case SET:
            case COLLECTION:
                List<Object> list = new ArrayList<>();
                list.add(buildContainerVal(containerTypeCodes, index + 1, innerVal));
                list.add(buildContainerVal(containerTypeCodes, index + 1, innerVal));
                return list;
            default:
                return buildContainerVal(containerTypeCodes, index + 1, innerVal);
        }
    }

    /**
     * 获取JsonMap结构中具体的 Val
     *
     * @param structureAndCommentInfo 结构信息+注释/注解信息
     * @param data                    全局数据
     * @param parentData              父数据
     * @return JsonMap结构中具体的 Val
     */
    protected Object getJsonMapVal(StructureAndCommentInfo structureAndCommentInfo, Map<String, Object> data, ReadOnlyMap parentData) {
        Object baseExampleVal = getBaseExampleVal(structureAndCommentInfo);
        if (baseExampleVal == null) {
            baseExampleVal = new Object();
        }
        return baseExampleVal;
    }

    public Object getBaseExampleVal(StructureAndCommentInfo structureAndCommentInfo) {
        CommentInfo commentInfo = structureAndCommentInfo.getCommentInfo();
        try {
            Integer fieldTypeCode = structureAndCommentInfo.getOriginalFieldTypeCode();
            PsiType psiType = structureAndCommentInfo.getPsiType();
            if (FieldType.BASE.getType().equals(fieldTypeCode)) {
                return BaseTypeUtil.getBaseDefaultVal(psiType, commentInfo);
            } else {
                String qName = psiType.getCanonicalText();
                return BaseTypeUtil.getInterfaceDefaultVal(qName, commentInfo);
            }
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (Exception e) {
            String fieldName = structureAndCommentInfo.getFieldName();
            String example = commentInfo.getExample("[未设置]");
            String errMsg = String.format("获取字段示例值时出错, 可能是字段类型转换失败! 字段名称: %s; 您设置的示例值:%s; ",
                    fieldName, example);
            ExceptionUtil.logException(e, errMsg);
            return null;
        }
    }

}
