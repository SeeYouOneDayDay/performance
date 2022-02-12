package io.github.xanderwang.asu;

import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

/**
 * a util class
 * @author xanderwang
 * @date: 20220108
 */
public class aUtil {

    protected static String logTag(String tag) {
        if (null == aConstants.globalTag || "".equals(aConstants.globalTag)) {
            return tag;
        }
        return aUtil.format(aConstants.tagFormat, aConstants.globalTag, tag);
    }

    public static String format(String formatStr, Object... args) {
        if (null == args || args.length == 0) {
            return formatStr;
        }
        return String.format(formatStr, args);
    }

    public static String memberToString(Member member) {
        if (member instanceof Method) {
            return ((Method) member).toString();
        }
        if (member instanceof Constructor) {
            return ((Constructor) member).toString();
        }
        if (null != member) {
            return member.getName();
        }
        return "null";
    }

    /**
     * 把 object 转出 json string
     *
     * @param obj
     * @return
     */
    public static String jsonString(Object obj) {
        JSONObject jsonObject = new JSONObject();
        Field[] objFields = obj.getClass().getDeclaredFields();
        try {
            for (Field field : objFields) {
                field.setAccessible(true);
                jsonObject.put(field.getName(), field.get(obj));
            }
        } catch (Exception e) {

        }
        return jsonObject.toString();
    }

}
