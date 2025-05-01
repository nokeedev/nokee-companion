package dev.nokee.companion.features;

interface BuildOperationLoggerRef {
	UseCount useCount();

	final class UseCount {
		private int count = 0;

		public int increment() {
			return ++count;
		}

		public int decrement() {
			return --count;
		}
	}
}
