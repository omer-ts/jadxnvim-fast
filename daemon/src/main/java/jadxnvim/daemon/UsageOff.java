package jadxnvim.daemon;

import jadx.api.usage.IUsageInfoCache;
import jadx.api.usage.IUsageInfoData;
import jadx.api.usage.IUsageInfoVisitor;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;

/**
 * Disables jadx's global usage (cross-reference) graph.
 *
 * <p>jadx's {@code UsageInfoVisitor} is a pre-decompile pass that, at load time, walks every class
 * and records who-uses-what into per-node {@code useIn} lists. On a 400k-class APK that graph is a
 * large, permanent slice of the heap and is only needed for find-usages. The visitor skips building
 * it entirely when the usage cache returns non-null data, so returning an empty {@link IUsageInfoData}
 * makes the pass a no-op: no traversal, no per-node usage lists, much lower memory.
 *
 * <p>Trade-off: {@code JavaNode.getUseIn()} is then empty, so find-usages falls back to a ripgrep
 * scan of the exported sources instead of the semantic graph.
 */
final class UsageOff {

	static final class EmptyData implements IUsageInfoData {
		@Override
		public void apply() {
			// nothing to apply
		}

		@Override
		public void applyForClass(ClassNode cls) {
			// nothing to apply
		}

		@Override
		public void visitUsageData(IUsageInfoVisitor visitor) {
			// no usage data to visit
		}
	}

	static final class Cache implements IUsageInfoCache {
		private final IUsageInfoData empty = new EmptyData();

		@Override
		public IUsageInfoData get(RootNode root) {
			return empty; // non-null => UsageInfoVisitor skips building the graph
		}

		@Override
		public void set(RootNode root, IUsageInfoData data) {
			// drop it
		}

		@Override
		public void close() {
			// nothing to close
		}
	}

	private UsageOff() {
	}
}
