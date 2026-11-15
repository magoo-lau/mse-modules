package vip.mseapp.ai.kb.chunk;

/**
 * 切片参数（取自知识库配置）。
 *
 * @param chunkSize    切片目标大小（token 估算值）
 * @param chunkOverlap 相邻切片重叠大小（token 估算值，general/book 生效）
 */
public record ChunkParams(int chunkSize, int chunkOverlap) {

    public ChunkParams {
        chunkSize = Math.max(100, chunkSize <= 0 ? 800 : chunkSize);
        chunkOverlap = Math.min(Math.max(0, chunkOverlap < 0 ? 100 : chunkOverlap), chunkSize / 2);
    }

    /**
     * token 估算：CJK 字符约 1 字符 1 token，其余按 4 字符 1 token 折算。
     * 切片长度控制用估算值即可，无需精确分词。
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0x3000 && c <= 0x303F)
                || (c >= 0xFF00 && c <= 0xFFEF);
    }
}
