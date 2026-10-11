package com.termux.app.zhicode.storage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * 把 {@code org.json} 的值编成紧凑二进制，供 SQLite 的 BLOB 列使用。
 *
 * <h3>为什么不直接写 JSON 文本</h3>
 * 会话里存的全是 {@code JSONObject} / {@code JSONArray}（引擎与三个协议适配器都以它为边界）。
 * 直接把 {@code toString()} 写进 `TEXT` 列最省事，但那是**把 JSON 文本当成落盘格式**：
 * 每个对象都要重复写一遍键名、每个数字都要变成十进制字符、每次读取都要重新解析整棵树，
 * 而解析失败又要有一套「宽容读取」的容错分支。编成二进制之后：
 *
 * <ul>
 *   <li>键名只在同一个对象里出现一次，且长度用变长整数而不是固定 4 字节；</li>
 *   <li>整数/布尔/null 各占 1～9 字节，不再有字符→数字的来回转换；</li>
 *   <li><b>没有「解析到一半失败」这种状态</b>：长度前缀对不上就是数据损坏，
 *       可以整块拒绝，而不是像 JSONL 那样留着半行。</li>
 * </ul>
 *
 * <h3>格式</h3>
 * <pre>
 *   byte 0        格式版本（当前为 1），用于将来的迁移
 *   之后          一个值
 *
 *   值的编码（首字节为标签）：
 *     0x00  null
 *     0x01  false
 *     0x02  true
 *     0x03  整数（变长有符号，zigzag）
 *     0x04  浮点（8 字节 IEEE-754）
 *     0x05  字符串（变长长度 + UTF-8 字节）
 *     0x06  对象（变长成员数 + 每个成员：[键字符串][值]）
 *     0x07  数组（变长元素数 + 每个元素：[值]）
 * </pre>
 *
 * <h3>它不做什么</h3>
 * 不压缩、不加密、不做跨版本字段重命名。它的唯一职责是「同一份树进、同一份树出」，
 * 语义等价性由 {@link SessionDatabase} 的读写路径保证。
 */
final class BinaryValueCodec {

    /** 写在每个 BLOB 开头的格式版本。改动编码方式时必须 +1，并保留旧版本的读取分支。 */
    static final int FORMAT_VERSION = 1;

    private static final byte TAG_NULL = 0x00;
    private static final byte TAG_FALSE = 0x01;
    private static final byte TAG_TRUE = 0x02;
    private static final byte TAG_INT = 0x03;
    private static final byte TAG_DOUBLE = 0x04;
    private static final byte TAG_STRING = 0x05;
    private static final byte TAG_OBJECT = 0x06;
    private static final byte TAG_ARRAY = 0x07;

    private BinaryValueCodec() {}

    // ------------------------------------------------------------ 编码

    /**
     * 编码一个 JSON 对象。
     *
     * <p>{@code null} 编成「空对象」而不是空的字节数组：读回来的必须是同一棵树，
     * 而不是一个"我读到了 null"的额外分支。
     */
    static byte[] encodeObject(JSONObject value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(FORMAT_VERSION);
        writeValue(out, value == null ? new JSONObject() : value);
        return out.toByteArray();
    }

    /** 编码一个 JSON 数组。 */
    static byte[] encodeArray(JSONArray value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(FORMAT_VERSION);
        writeValue(out, value == null ? new JSONArray() : value);
        return out.toByteArray();
    }

    // ------------------------------------------------------------ 解码

    /**
     * 解码一个对象。
     *
     * <p>字节为空或版本不认识时抛 {@link IOException}：这是**损坏**，不是"空数据"。
     * 悄悄返回空对象会让一条读不出来的历史看起来像"这条会话没有内容"，
     * 比直接报错更难排查。
     */
    static JSONObject decodeObject(byte[] data) throws IOException {
        Cursor cursor = open(data);
        Object value = readValue(cursor);
        if (!(value instanceof JSONObject)) throw new IOException("会话数据不是对象");
        return (JSONObject) value;
    }

    /** 解码一个数组。 */
    static JSONArray decodeArray(byte[] data) throws IOException {
        Cursor cursor = open(data);
        Object value = readValue(cursor);
        if (!(value instanceof JSONArray)) throw new IOException("会话数据不是数组");
        return (JSONArray) value;
    }

    private static Cursor open(byte[] data) throws IOException {
        if (data == null || data.length == 0) throw new IOException("会话数据为空");
        int version = data[0] & 0xff;
        if (version != FORMAT_VERSION) {
            throw new IOException("会话数据版本不支持：" + version + "（当前 " + FORMAT_VERSION + "）");
        }
        return new Cursor(data);
    }

    // ------------------------------------------------------------ 写入

    private static void writeValue(ByteArrayOutputStream out, Object value) throws IOException {
        if (value == null || JSONObject.NULL.equals(value)) {
            out.write(TAG_NULL);
            return;
        }
        if (value instanceof Boolean) {
            out.write(((Boolean) value) ? TAG_TRUE : TAG_FALSE);
            return;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte) {
            out.write(TAG_INT);
            writeVarLong(out, ((Number) value).longValue());
            return;
        }
        if (value instanceof Number) {
            // 浮点单独一个标签：把它折成整数会让 0.5 变成 0，而这类值出现在
            // 工具入参里（例如坐标、比例），静默取整是错的。
            out.write(TAG_DOUBLE);
            writeFixedLong(out, Double.doubleToRawLongBits(((Number) value).doubleValue()));
            return;
        }
        if (value instanceof JSONObject) {
            out.write(TAG_OBJECT);
            JSONObject object = (JSONObject) value;
            writeVarLong(out, object.length());
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                writeString(out, key);
                writeValue(out, object.opt(key));
            }
            return;
        }
        if (value instanceof JSONArray) {
            out.write(TAG_ARRAY);
            JSONArray array = (JSONArray) value;
            writeVarLong(out, array.length());
            for (int i = 0; i < array.length(); i++) writeValue(out, array.opt(i));
            return;
        }
        // 其余一律当字符串：org.json 自己也可能塞进别的东西，丢掉它们等于静默改内容。
        out.write(TAG_STRING);
        writeString(out, String.valueOf(value));
    }

    private static void writeString(ByteArrayOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        writeVarLong(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    /** zigzag 变长整数：小数值占 1 字节，负数不额外变长。 */
    private static void writeVarLong(ByteArrayOutputStream out, long value) {
        long zigzag = (value << 1) ^ (value >> 63);
        while ((zigzag & ~0x7FL) != 0L) {
            out.write((int) ((zigzag & 0x7FL) | 0x80L));
            zigzag >>>= 7;
        }
        out.write((int) zigzag);
    }

    private static void writeFixedLong(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) out.write((int) ((value >>> shift) & 0xffL));
    }

    // ------------------------------------------------------------ 读取

    private static Object readValue(Cursor cursor) throws IOException {
        byte tag = cursor.readByte();
        switch (tag) {
            case TAG_NULL:
                return JSONObject.NULL;
            case TAG_FALSE:
                return Boolean.FALSE;
            case TAG_TRUE:
                return Boolean.TRUE;
            case TAG_INT:
                return cursor.readVarLong();
            case TAG_DOUBLE:
                return Double.longBitsToDouble(cursor.readFixedLong());
            case TAG_STRING:
                return cursor.readString();
            case TAG_OBJECT: {
                long size = cursor.readVarLong();
                if (size < 0 || size > cursor.remaining()) throw new IOException("会话对象长度非法");
                JSONObject object = new JSONObject();
                for (long i = 0; i < size; i++) {
                    String key = cursor.readString();
                    Object value = readValue(cursor);
                    try {
                        object.put(key, JSONObject.NULL.equals(value) ? JSONObject.NULL : value);
                    } catch (Exception rejected) {
                        throw new IOException("会话对象成员写入失败：" + key, rejected);
                    }
                }
                return object;
            }
            case TAG_ARRAY: {
                long size = cursor.readVarLong();
                if (size < 0 || size > cursor.remaining()) throw new IOException("会话数组长度非法");
                JSONArray array = new JSONArray();
                for (long i = 0; i < size; i++) array.put(readValue(cursor));
                return array;
            }
            default:
                throw new IOException("会话数据的标签不认识：" + tag);
        }
    }

    /** 只前进不回退的读取游标。任何越界都抛 IOException，而不是返回 0 继续读。 */
    private static final class Cursor {
        private final byte[] data;
        private int position;

        Cursor(byte[] data) {
            this.data = data;
            this.position = 1; // 跳过版本字节
        }

        int remaining() {
            return data.length - position;
        }

        byte readByte() throws IOException {
            if (position >= data.length) throw new IOException("会话数据意外结束");
            return data[position++];
        }

        long readVarLong() throws IOException {
            long result = 0L;
            int shift = 0;
            while (true) {
                byte current = readByte();
                result |= (long) (current & 0x7F) << shift;
                if ((current & 0x80) == 0) break;
                shift += 7;
                if (shift > 63) throw new IOException("会话整数字段过长");
            }
            return (result >>> 1) ^ -(result & 1L);
        }

        long readFixedLong() throws IOException {
            if (remaining() < 8) throw new IOException("会话数据意外结束");
            long value = 0L;
            for (int i = 0; i < 8; i++) value = (value << 8) | (data[position++] & 0xffL);
            return value;
        }

        String readString() throws IOException {
            long length = readVarLong();
            if (length < 0 || length > remaining()) throw new IOException("会话字符串长度非法");
            String value = new String(data, position, (int) length, StandardCharsets.UTF_8);
            position += (int) length;
            return value;
        }
    }
}
