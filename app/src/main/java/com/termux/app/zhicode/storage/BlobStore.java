package com.termux.app.zhicode.storage;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 会话大内容的落盘位置：{@code ~/.zhicode/blobs/aa/bb/<sha256>}。
 *
 * <h3>为什么要外置，而不是塞进数据库</h3>
 * 会话里真正巨大的东西是**工具输出**（一次 {@code Bash} 的完整 stdout、一次 {@code Read}
 * 的文件正文）与**图片附件**（base64）。把它们放进 {@code sessions.db} 的 BLOB 列有两个
 * 后果：数据库文件会随对话线性膨胀（备份、{@code VACUUM}、迁移全都变慢），
 * 而且删掉一条会话之后空间不会立刻还回来。
 *
 * <p>外置之后，数据库里只留 64 位十六进制哈希与字节数，大内容按**内容寻址**存放：
 * 同一段重复的输出（例如同一个命令被跑了两遍）在磁盘上只有一份。
 *
 * <h3>为什么按哈希分两级目录</h3>
 * 一个目录里堆几十万个文件会让 {@code readdir} 与文件系统本身变慢。用哈希前两位
 * 分一级、再两位分一级，256×256 个桶，每个桶里的文件数可控。
 *
 * <h3>写入顺序不能颠倒</h3>
 * <pre>
 *   写临时文件 → fsync → rename 到最终路径 → 返回哈希
 * </pre>
 * 调用方拿到哈希之后才在一个数据库事务里写入引用。<b>先写 Blob，后写引用</b>——
 * 反过来的话，事务提交成功而 Blob 还没落盘，就得到一个指向不存在文件的会话；
 * 而按这个顺序，最坏情况只是多出一个暂时没人引用的 Blob，下次回收时删掉。
 *
 * <h3>它不负责回收</h3>
 * 删除由 {@link SessionDatabase#pruneOrphanBlobs} 做：那个动作需要同时看
 * {@code blobs} 表与各引用列，放在这里会让本类依赖数据库。
 */
final class BlobStore {

    /** 超过这个字节数的内容才外置。与旧实现的 8 KB 门槛保持一致。 */
    static final int THRESHOLD_BYTES = 8 * 1024;

    /** 单块上限。超过它说明调用方传错了东西，而不是"内容有点大"。 */
    static final int MAX_BLOB_BYTES = 64 * 1024 * 1024;

    private static final String BLOBS_DIR = "blobs";

    private static final BlobStore SHARED = new BlobStore();

    static BlobStore shared() {
        return SHARED;
    }

    /** Blob 根目录：{@code ~/.zhicode/blobs}。 */
    static File root() {
        return new File(TermuxConstants.dataDir(), BLOBS_DIR);
    }

    /**
     * 写入一段字节，返回它的 SHA-256 十六进制。
     *
     * <p>已经存在**且长度一致**时直接复用，不再写一遍。只在长度不一致时覆盖 ——
     * 哈希相同而长度不同意味着文件被外力破坏过（或者是磁盘错误），那时必须重写。
     */
    String put(byte[] data) throws IOException {
        if (data == null) data = new byte[0];
        if (data.length > MAX_BLOB_BYTES) throw new IOException("会话大内容超过上限");
        String hash = sha256(data);
        File target = fileFor(hash);
        if (target.isFile() && target.length() == data.length) return hash;
        File parent = target.getParentFile();
        if (parent != null) AtomicFiles.ensureDirectory(parent, "会话大内容目录");
        AtomicFiles.publish(target, data);
        return hash;
    }

    /** 写入一段文本。 */
    String putText(String text) throws IOException {
        return put((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 读回一段字节，并校验摘要。
     *
     * <p>校验是必须的：内容寻址的全部价值就在于「拿到的就是当初存进去的那一份」。
     * 少了它，一次局部损坏会变成界面上一段看似正常、实则被改写过的内容。
     */
    byte[] get(String hash) throws IOException {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IOException("会话大内容标识非法");
        byte[] data = AtomicFiles.readBytes(fileFor(hash), MAX_BLOB_BYTES);
        if (!sha256(data).equals(hash)) throw new IOException("会话大内容校验失败");
        return data;
    }

    /** 是否存在（用于回收前的判断，不读内容）。 */
    boolean exists(String hash) {
        return hash != null && hash.matches("[0-9a-f]{64}") && fileFor(hash).isFile();
    }

    /** 删除一个 Blob；不存在也算成功。 */
    boolean delete(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) return false;
        File file = fileFor(hash);
        return !file.exists() || file.delete();
    }

    private static File fileFor(String hash) {
        return new File(new File(new File(root(), hash.substring(0, 2)), hash.substring(2, 4)), hash);
    }

    /**
     * 一段字节的 SHA-256 十六进制。
     *
     * <p>包内可见：导入器要拿它校验**旧格式**里外置的大内容（那些文件在旧会话目录下，
     * 不在本类的 {@code blobs/} 根里，所以走不了 {@link #get}）。
     * 校验必须用同一套算法，否则会出现「导入时算一遍、以后读时再算一遍、两边对不上」。
     */
    static String hashOf(byte[] data) throws IOException {
        return sha256(data == null ? new byte[0] : data);
    }

    private static String sha256(byte[] data) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format(Locale.US, "%02x", value & 255));
            return result.toString();
        } catch (Exception unavailable) {
            throw new IOException("无法计算会话大内容摘要", unavailable);
        }
    }
}
