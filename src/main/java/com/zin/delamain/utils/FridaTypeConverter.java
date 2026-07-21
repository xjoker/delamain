package com.zin.delamain.utils;

import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.instructions.args.PrimitiveType;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility class for converting JADX ArgType to Frida-compatible type strings.
 */
public class FridaTypeConverter {

    public static String toFridaType(ArgType type) {
        if (type == null) {
            return "void";
        }

        if (type.isArray()) {
            int dimension = type.getArrayDimension();
            ArgType rootElement = type.getArrayRootElement();
            String prefix = "[".repeat(dimension);

            if (rootElement.isPrimitive()) {
                return prefix + getPrimitiveShortName(rootElement.getPrimitiveType());
            } else if (rootElement.isObject()) {
                // getObject() returns dot-separated names (e.g. "com.example.Outer$Inner"),
                // but JNI array descriptors require slash-separated paths.
                // Inner classes already use '$' which is preserved by this replacement.
                String jniName = rootElement.getObject().replace('.', '/');
                return prefix + "L" + jniName + ";";
            } else {
                return prefix + "Ljava/lang/Object;";
            }
        }

        if (type.isPrimitive()) {
            return type.getPrimitiveType().toString().toLowerCase();
        }

        if (type.isObject()) {
            return type.getObject();
        }

        return type.toString();
    }

    public static String toFridaOverloadString(List<ArgType> types) {
        if (types == null || types.isEmpty()) {
            return "";
        }

        return types.stream()
            .map(FridaTypeConverter::toFridaType)
            .map(t -> "'" + t + "'")
            .collect(Collectors.joining(", "));
    }

    private static String getPrimitiveShortName(PrimitiveType type) {
        switch (type) {
            case INT: return "I";
            case BOOLEAN: return "Z";
            case BYTE: return "B";
            case SHORT: return "S";
            case CHAR: return "C";
            case LONG: return "J";
            case FLOAT: return "F";
            case DOUBLE: return "D";
            case VOID: return "V";
            default: return "L";
        }
    }
}
