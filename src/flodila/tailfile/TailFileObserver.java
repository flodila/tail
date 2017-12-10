package flodila.tailfile;

import java.util.List;

/**
 * What you need to implement in order to get the changes for the file you want to watch
 */
public interface TailFileObserver {

	/**
	 * @param state
	 * @param newLines null if file does not exist
	 */
	void update(FileState state, List<Line> newLines, String message);

	// ----------------------------------------------------
	// type
	//
	public enum FileState {
		DOES_NOT_EXIST,
		RESET,
		CONTINUED,
		ERROR;

		public FileState and(FileState other) {
			if (this == CONTINUED && other == CONTINUED) {
				return CONTINUED;
			} else {
				return ERROR;
			}
		}
	}

	public static final class Line {
		public final long lineno;
		public final String content;
		public Line(long lineno, String content) {
			this.lineno = lineno;
			this.content = content;
		}
	}
}
