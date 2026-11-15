package ${package}.loader.api.metadata;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fabric Loader's net.fabricmc.loader.api.metadata.CustomValue: one value of fabric.mod.json's custom block, which
 * convert keeps as JSON in the converted mod's modproperties (fabric.custom).
 */
public class CustomValue {

    public enum CvType {
        OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL
    }

    public interface CvObject extends Iterable<Map.Entry<String, CustomValue>> {
        int size();

        boolean containsKey(String key);

        CustomValue get(String key);

        CvType getType();
    }

    public interface CvArray extends Iterable<CustomValue> {
        int size();

        CustomValue get(int index);

        CvType getType();
    }

    final JsonElement json;

    CustomValue(JsonElement json) {
        this.json = json;
    }

    static CustomValue of(JsonElement json) {
        if (json.isJsonObject()) return new ObjectValue(json);
        if (json.isJsonArray()) return new ArrayValue(json);
        return new CustomValue(json);
    }

    public CvType getType() {
        if (json.isJsonObject()) return CvType.OBJECT;
        if (json.isJsonArray()) return CvType.ARRAY;
        if (json.isJsonNull()) return CvType.NULL;
        var primitive = json.getAsJsonPrimitive();
        return primitive.isBoolean() ? CvType.BOOLEAN : primitive.isNumber() ? CvType.NUMBER : CvType.STRING;
    }

    public CvObject getAsObject() {
        if (this instanceof ObjectValue o) return o;
        throw new ClassCastException("not an object: " + json);
    }

    public CvArray getAsArray() {
        if (this instanceof ArrayValue a) return a;
        throw new ClassCastException("not an array: " + json);
    }

    public String getAsString() {
        return json.getAsString();
    }

    public Number getAsNumber() {
        return json.getAsNumber();
    }

    public boolean getAsBoolean() {
        return json.getAsBoolean();
    }

    static final class ObjectValue extends CustomValue implements CvObject {
        private final Map<String, CustomValue> values = new LinkedHashMap<>();

        ObjectValue(JsonElement json) {
            super(json);
            for (var entry : json.getAsJsonObject().entrySet()) values.put(entry.getKey(), of(entry.getValue()));
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public boolean containsKey(String key) {
            return values.containsKey(key);
        }

        @Override
        public CustomValue get(String key) {
            return values.get(key);
        }

        @Override
        public Iterator<Map.Entry<String, CustomValue>> iterator() {
            return values.entrySet().iterator();
        }
    }

    static final class ArrayValue extends CustomValue implements CvArray {
        private final List<CustomValue> values = new ArrayList<>();

        ArrayValue(JsonElement json) {
            super(json);
            for (JsonElement element : json.getAsJsonArray()) values.add(of(element));
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public CustomValue get(int index) {
            return values.get(index);
        }

        @Override
        public Iterator<CustomValue> iterator() {
            return values.iterator();
        }
    }
}
