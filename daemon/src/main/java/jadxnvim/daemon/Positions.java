package jadxnvim.daemon;

/**
 * Conversions between jadx character offsets (indices into the decompiled {@code String}) and
 * editor (line, column) coordinates.
 *
 * <p>Line is 1-based; column is a 0-based count of characters (Java {@code char} / UTF-16 code
 * units) from the start of the line. The Neovim client converts its byte columns to/from these
 * character columns. Decompiled Java is ASCII-dominant, so this is almost always an identity
 * mapping; supplementary (non-BMP) characters in string literals are the only edge case.
 */
final class Positions {

	/** Offset (char index) -> {line, col}. */
	static int[] toLineCol(String code, int offset) {
		int limit = Math.min(offset, code.length());
		int line = 1;
		int col = 0;
		for (int i = 0; i < limit; i++) {
			if (code.charAt(i) == '\n') {
				line++;
				col = 0;
			} else {
				col++;
			}
		}
		return new int[] { line, col };
	}

	/** {line, col} -> offset (char index). */
	static int toOffset(String code, int line, int col) {
		int i = 0;
		int curLine = 1;
		int len = code.length();
		while (curLine < line && i < len) {
			if (code.charAt(i) == '\n') {
				curLine++;
			}
			i++;
		}
		int offset = i + col;
		return Math.min(offset, len);
	}

	/** The text of the line containing the given char offset (without the trailing newline). */
	static String lineText(String code, int offset) {
		int len = code.length();
		int pos = Math.min(Math.max(offset, 0), len);
		int start = pos;
		while (start > 0 && code.charAt(start - 1) != '\n') {
			start--;
		}
		int end = pos;
		while (end < len && code.charAt(end) != '\n') {
			end++;
		}
		return code.substring(start, end);
	}

	private Positions() {
	}
}
