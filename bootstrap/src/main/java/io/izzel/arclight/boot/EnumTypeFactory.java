package io.izzel.arclight.boot;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * A Gson {@link TypeAdapterFactory} for enum types that silently handles
 * unknown enum constants instead of throwing {@link AssertionError}.
 *
 * <p>Arclight dynamically adds new constants to Bukkit enums at runtime via
 * {@link io.izzel.arclight.api.EnumHelper}. Gson's built-in enum adapter caches
 * enum constants at construction time and throws an {@link AssertionError} when
 * it encounters a constant added after initialization.</p>
 *
 * <p>This factory replaces Gson's default enum adapter with one that:</p>
 * <ul>
 *   <li>Reads enum constants by {@link SerializedName} annotation (same as Gson)</li>
 *   <li>Falls back to {@link Enum#toString()} for constants without annotations</li>
 *   <li>Returns {@code null} instead of throwing for unknown constant names</li>
 * </ul>
 *
 * <p>Source: adapted from Gson's internal {@code TypeAdapters.EnumTypeAdapter},
 * Apache License 2.0. Modified to suppress the assertion error thrown when a
 * dynamically added enum constant has no corresponding field.</p>
 */
public class EnumTypeFactory implements TypeAdapterFactory {

   @Override
@SuppressWarnings({"rawtypes", "unchecked"})
public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
    Class<? super T> rawType = type.getRawType();
    if (!Enum.class.isAssignableFrom(rawType) || rawType == Enum.class) {
        return null;
    }
    if (!rawType.isEnum()) {
        rawType = rawType.getSuperclass();
    }
    // Raw type intentional — Java cannot infer the Enum bound from ? super T
    return (TypeAdapter<T>) new EnumTypeAdapter(rawType);
}

    /**
     * Type adapter for a specific enum class.
     *
     * <p>Builds bidirectional lookup maps at construction time using both
     * {@link SerializedName} and {@link Enum#toString()} as fallbacks.</p>
     *
     * @param <T> the enum type
     */
    private static final class EnumTypeAdapter<T extends Enum<T>> extends TypeAdapter<T> {

        /** Maps JSON string value → enum constant. */
        private final Map<String, T> nameToConstant = new HashMap<>();

        /** Maps enum constant → JSON string value. */
        private final Map<T, String> constantToName = new HashMap<>();

        /**
         * Secondary fallback: maps {@link Enum#toString()} → constant.
         * Used when the constant's JSON name doesn't match.
         */
        private final Map<String, T> stringToConstant = new HashMap<>();

        EnumTypeAdapter(Class<T> classOfT) {
            for (T constant : classOfT.getEnumConstants()) {
                String jsonName = constant.name();

                // Check for @SerializedName annotation on the enum field
                SerializedName annotation = null;
                try {
                    annotation = classOfT.getField(constant.name())
                        .getAnnotation(SerializedName.class);
                } catch (NoSuchFieldException ignored) {
                    // Dynamically added enum constants have no field — skip gracefully
                }

                if (annotation != null) {
                    jsonName = annotation.value();
                    // Also register alternate names from the annotation
                    for (String alternate : annotation.alternate()) {
                        nameToConstant.put(alternate, constant);
                    }
                }

                nameToConstant.put(jsonName, constant);
                constantToName.put(constant, jsonName);
                stringToConstant.put(constant.toString(), constant);
            }
        }

        @Override
        public T read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            String value = in.nextString();
            // Try primary name lookup, then fall back to toString() lookup
            T result = nameToConstant.get(value);
            return (result != null) ? result : stringToConstant.get(value);
        }

        @Override
        public void write(JsonWriter out, T value) throws IOException {
            out.value(value == null ? null : constantToName.get(value));
        }
    }
}