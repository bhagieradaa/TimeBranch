package dev.timebranch;

/** Maps JavaFX's LF-only editor view back to the stored text. */
final class EditorText {
    private EditorText() {}

    static String display(String source) {
        return source.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "\uFFFD");
    }

    static String applyEdit(String source, int start, int end, String inserted) {
        if (source.indexOf('\r') < 0) {
            return source.substring(0, start) + inserted + source.substring(end);
        }
        int sourceStart = sourceOffset(source, start, 0);
        int sourceEnd = sourceOffset(source, end - start, sourceStart);
        int lf = source.indexOf('\n');
        int cr = source.indexOf('\r');
        String ending = lf >= 0 && lf < cr ? "\n" : source.startsWith("\r\n", cr) ? "\r\n" : "\r";
        return source.substring(0, sourceStart) + inserted.replace("\n", ending) + source.substring(sourceEnd);
    }

    private static int sourceOffset(String source, int characters, int offset) {
        for (int i = 0; i < characters; i++) {
            if (source.charAt(offset++) == '\r' && offset < source.length() && source.charAt(offset) == '\n') {
                offset++;
            }
        }
        return offset;
    }
}
