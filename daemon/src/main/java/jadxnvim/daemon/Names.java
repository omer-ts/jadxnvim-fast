package jadxnvim.daemon;

import java.util.ArrayList;
import java.util.List;

import jadx.core.deobf.NameMapper;

/**
 * Computes the name jadx shows for a class whose raw dex name is not a valid Java identifier, so the
 * index can be searched by BOTH the original (raw) name and the jadx-rendered name.
 *
 * <p>jadx renames invalid class names deterministically: an all-digit name {@code 000} becomes
 * {@code AnonymousClass000}, and a digit-prefixed name {@code 0Ac} becomes {@code C0Ac}. We reproduce
 * that from the raw name alone (no decompilation) using jadx's {@link NameMapper} to detect validity,
 * and index a few candidate forms so a search matches whichever jadx actually used.
 */
final class Names {

	private Names() {
	}

	static boolean isValidSimple(String seg) {
		return !seg.isEmpty() && NameMapper.isValidIdentifier(seg) && !NameMapper.isReserved(seg);
	}

	private static boolean allDigits(String s) {
		if (s.isEmpty()) {
			return false;
		}
		for (int i = 0; i < s.length(); i++) {
			if (!Character.isDigit(s.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	// jadx's primary rename for one invalid name segment.
	private static String fixSegment(String seg) {
		if (isValidSimple(seg)) {
			return seg;
		}
		if (allDigits(seg)) {
			return "AnonymousClass" + seg;
		}
		return "C" + seg;
	}

	/**
	 * The jadx-rendered simple name for a (possibly nested, {@code Outer$Inner}) raw simple name, or
	 * the same string if it is already a valid identifier. jadx fixes each {@code $}-segment
	 * independently.
	 */
	static String jadxSimpleName(String rawSimple) {
		if (rawSimple.indexOf('$') < 0) {
			return fixSegment(rawSimple);
		}
		String[] segs = rawSimple.split("\\$", -1);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < segs.length; i++) {
			if (i > 0) {
				sb.append('$');
			}
			sb.append(fixSegment(segs[i]));
		}
		return sb.toString();
	}

	/** True if the raw simple name is renamed by jadx (so it has a distinct alias). */
	static boolean isRenamed(String rawSimple) {
		return !rawSimple.equals(jadxSimpleName(rawSimple));
	}

	/**
	 * Candidate simple-name search forms for a raw simple name: the raw name plus, when jadx would
	 * rename it, both the {@code AnonymousClass<n>} and {@code C<n>} forms (over-indexed so a search
	 * matches whichever jadx used). Used only to build the search text, not for display.
	 */
	static List<String> searchVariants(String rawSimple) {
		List<String> out = new ArrayList<>();
		out.add(rawSimple);
		if (rawSimple.indexOf('$') < 0 && !isValidSimple(rawSimple)) {
			out.add("C" + rawSimple);
			if (allDigits(rawSimple)) {
				out.add("AnonymousClass" + rawSimple);
			}
		} else if (isRenamed(rawSimple)) {
			out.add(jadxSimpleName(rawSimple));
		}
		return out;
	}
}
