package flodila.tailfile;

import java.io.File;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import flodila.tailfile.TailFileObserver.FileState;

/**
 * The backend of the tail -f servlet; no servlet imports here - you might want to use this anywhere else as well
 */
public class TailFileWatcher {
	public static final long DEFAULT_MAX_MEM_MAP_KILOBYTES = 1024; // 1 MiB
	public static final int DEFAULT_MAX_LINES = 1024;
	public static final long DEFAULT_MIN_TIME_GAP_MILLISECONDS = 50;
	private final TailFileWatcherThread mythread;

	// ----------------------------------------------------
	// life
	//
	public TailFileWatcher() {
		this.mythread = new TailFileWatcherThread();
		this.mythread.start();
	}

	public void shutdown() {
		this.mythread.shutdown();
	}

	// ----------------------------------------------------
	// API
	//
	/**
	 * @param f file to tail
	 * @param charset of file
	 * @param observer to send the tail content of the file to
	 * @param maxMemMapKiB maximum number of Kilobytes of the end of the file to map into memory, default 
	 * @param maxLineBufferCount maximum number of lines sent to the observer
	 * @param minTimeGapMillis minium number of milliseconds between observer calls
	 * @return handle to unwatch
	 */
	public Long watch(File f, Charset charset, TailFileObserver observer, Integer maxMemMapKiB, Integer maxLineBufferCount, Integer minTimeGapMillis) {
		return this.mythread.watch(f, charset, observer, maxMemMapKiB, maxLineBufferCount, minTimeGapMillis);
	}
	public void unwatch(Long handle) {
		this.mythread.unwatch(handle);
	}

	// ----------------------------------------------------
	// Watcher Thread
	//
	private static final class TailFileWatcherThread extends Thread {
		private static int threadno = 0;
		private volatile boolean shutdownRequested = false;
		private final WatchService watchService;
		private long watchHandleCount = 0L;
		private int filesTouchedSinceLastGarbageCollection = 0;
		private final Map<String, WatchedDir> watchedDirIndex = new HashMap<>(); // used in run() only
		private final Map<Long, TailWatchedFile> handle2twf = new ConcurrentHashMap<>(); // added to outside and removed from inside run()
		private final Map<Long, PendingHandleAction> handle2pendingAction = new ConcurrentHashMap<>(); // added to outside and removed from inside run()
		private final PendingObserverFodder pendingObserverFodder = new PendingObserverFodder();

		// -------------------
		// life
		//
		public TailFileWatcherThread() {
			super("tail-file-watcher-"+(threadno++));
			try {
				this.watchService = FileSystems.getDefault().newWatchService();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		public void shutdown() {
			shutdownRequested = true;
			interrupt();
			try {
				join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		// -----------------
		// API
		//
		public Long watch(File f, Charset charset, TailFileObserver observer, Integer maxMemMapKiB, Integer maxLineBufferCount, Integer minTimeGapMillis) {
			if (f.isDirectory()) {
				throw new IllegalArgumentException("It's a directory: "+f);
			}
			Path realPath = f.toPath();
			try {
				realPath = realPath.getParent().toRealPath().resolve(realPath.getFileName());
			} catch (IOException e) {
				throw new IllegalArgumentException("Could not get the real path of "+f, e);
			}
			final Long handle = Long.valueOf(watchHandleCount++);
			final long effMaxMemMapKiB = maxMemMapKiB != null ? maxMemMapKiB.longValue() : DEFAULT_MAX_MEM_MAP_KILOBYTES;
			final int effMaxLineBufferCount = maxLineBufferCount != null ? maxLineBufferCount.intValue() : DEFAULT_MAX_LINES;
			final long effMinTimeGapMillis = minTimeGapMillis != null ? minTimeGapMillis.longValue() : DEFAULT_MIN_TIME_GAP_MILLISECONDS;
			final TailWatchedFile twf = new TailWatchedFile(handle, realPath, charset, observer, effMaxMemMapKiB, effMaxLineBufferCount, effMinTimeGapMillis);
			handle2twf.put(handle, twf);
			handle2pendingAction.put(handle, PendingHandleAction.WATCH);
			return handle;
		}

		public void unwatch(Long handle) {
			if (handle != null) {
				handle2pendingAction.put(handle, PendingHandleAction.UNWATCH);
			}
		}

		// -----------------
		// Thread
		//
		@Override
		public void run() {
			run: while (true) {
				if (runShutdown()) {
					break run;
				}
				try {
					runPendingHandleActions();
					runPendingObserverFodder();
					runPollWatchService();
				} catch (InterruptedException e) {
					System.out.println("Interrupted "+getName());
				}
			}
		}

		// -----------------
		// extracts
		//
		private boolean runShutdown() {
			if (shutdownRequested) {
				System.out.println("Shutdown of "+getName()+" requested");
				for (WatchedDir watchedDir : watchedDirIndex.values()) {
					watchedDir.unwatch(handle2twf);
				}
				watchedDirIndex.clear();
				handle2twf.clear();
				try {
					watchService.close();
				} catch (IOException e) {
					e.printStackTrace();
				}

				return true;
			} else {
				return false;
			}
		}

		private void maybeGarbageCollect() {
			filesTouchedSinceLastGarbageCollection++;
			if (filesTouchedSinceLastGarbageCollection >= 512) {
				System.gc();
				filesTouchedSinceLastGarbageCollection = 0;
			}
		}

		private void runPendingHandleActions() {
			final Set<Long> handleSet = this.handle2pendingAction.keySet();
			final Map<Long, PendingHandleAction> handle2pendingAction = new HashMap<>(handleSet.size());
			for (Long handle : handleSet) {
				handle2pendingAction.put(handle, this.handle2pendingAction.remove(handle));
			}
			for (Map.Entry<Long, PendingHandleAction> entry : handle2pendingAction.entrySet()) {
				final Long handle = entry.getKey();
				final PendingHandleAction action = entry.getValue();
				final TailWatchedFile twf = this.handle2twf.get(handle);
				if (twf == null) {
					continue;
				}
				final String dir = twf.dirPath.toString();
				WatchedDir watchedDir = this.watchedDirIndex.get(dir);
				switch (action) {
				case WATCH:
					if (watchedDir == null) {
						WatchKey watchKey;
						try {
							watchKey = twf.dirPath.register(watchService,
									StandardWatchEventKinds.ENTRY_CREATE,
									StandardWatchEventKinds.ENTRY_DELETE,
									StandardWatchEventKinds.ENTRY_MODIFY);
						} catch (IOException e) {
							twf.message = e.toString();
							watchKey = null;
							e.printStackTrace();
						}
						watchedDir = new WatchedDir(watchKey);
						this.watchedDirIndex.put(dir, watchedDir);
					}
					watchedDir.handles.add(handle);
					final boolean rtfo = twf.readyToFeedObserver();
					if (twf.fileExists()) {
						if (rtfo) {
							twf.feedObserver(StandardWatchEventKinds.ENTRY_CREATE);
							pendingObserverFodder.remove(twf.handle);
							maybeGarbageCollect();
						} else {
							pendingObserverFodder.put(twf.handle, StandardWatchEventKinds.ENTRY_CREATE);
						}
					} else {
						if (rtfo) {
							twf.feedObserver(StandardWatchEventKinds.ENTRY_DELETE);
							pendingObserverFodder.remove(twf.handle);
						} else {
							pendingObserverFodder.put(twf.handle, StandardWatchEventKinds.ENTRY_DELETE);
						}
					}
					break;
				case UNWATCH:
					if (watchedDir != null) {
						watchedDir.handles.remove(handle);
						if (watchedDir.handles.isEmpty()) {
							this.watchedDirIndex.remove(dir);
							if (watchedDir.watchKey != null) {
								watchedDir.watchKey.cancel();
							}
						}
						twf.close();
					}
					this.handle2twf.remove(handle);
					break;
				default:
					throw new IllegalStateException("Unknown action: "+action);
				}
			}
		}

		private void runPendingObserverFodder() {
			final Set<Entry<Long,Kind<Path>>> entrySet = pendingObserverFodder.entrySet();
			final Set<Long> handlesToBeRemoved = new HashSet<>();
			for (Entry<Long, Kind<Path>> entry : entrySet) {
				final Long handle = entry.getKey();
				final TailWatchedFile twf = handle2twf.get(handle);
				if (twf != null) {
					if (twf.readyToFeedObserver()) {
						twf.feedObserver(entry.getValue());
						handlesToBeRemoved.add(handle);
						maybeGarbageCollect();
					}
				} else {
					handlesToBeRemoved.add(handle);
				}
			}
			for (Long handle : handlesToBeRemoved) {
				pendingObserverFodder.remove(handle);
			}
		}

		private void runPollWatchService() throws InterruptedException {
			final WatchKey watchKey = watchService.poll(50, TimeUnit.MILLISECONDS);
			if (watchKey == null) {
				return;
			}
			final Watchable watchable = watchKey.watchable();
			if (watchable == null) {
				System.out.println("watchable == null");
				return;
			}
			final String dir = watchable.toString();
			final WatchedDir watchedDir = watchedDirIndex.get(dir);
			if (watchedDir == null) {
				System.out.println("watchedDir == null for "+dir);
				return;
			}
			final Map<Path, List<TailWatchedFile>> path2twf = new TreeMap<>();
			for (Long handle: watchedDir.handles) {
				final TailWatchedFile twf = handle2twf.get(handle);
				if (twf != null) {
					List<TailWatchedFile> twfs = path2twf.get(twf.fnamePath);
					if (twfs == null) {
						twfs = new LinkedList<>();
						path2twf.put(twf.fnamePath, twfs);
					}
					twfs.add(twf);
				}
			}
			for (WatchEvent<?> evObj : watchKey.pollEvents()) {
				@SuppressWarnings("unchecked")
				final WatchEvent<Path> ev = (WatchEvent<Path>) evObj;
				final Kind<Path> kind = ev.kind();
				final Path fnamePath = ev.context();
				final List<TailWatchedFile> twfs = path2twf.get(fnamePath);
				if (twfs != null) {
					for (TailWatchedFile twf : twfs) {
						if (twf.readyToFeedObserver()) {
							twf.feedObserver(kind);
							pendingObserverFodder.remove(twf.handle);
							maybeGarbageCollect();
						} else {
							pendingObserverFodder.put(twf.handle, kind);
						}
					}
				}
			}
			watchKey.reset();
		}

		// -----------------
		// type
		//
		private static final class TailWatchedFile {
			public final Long handle;
			public final Path dirPath;
			public final Path fnamePath;
			public final CharsetDecoder decoder;
			public final TailFileObserver observer;
			private FileChannel channel = null;
			private long lastPos = 0L;
			private long lineNo = 0L;
			public String message = "untouched";
			boolean wasLF = false;
			final StringBuilder lineBld = new StringBuilder();
			private final long maxMemMapBytes;
			private final int maxLineBufferCount;
			private final long minTimeGapMillis;
			private long lastFedObserverEmil;
			public TailWatchedFile(Long handle, Path path, Charset charset, TailFileObserver observer, long maxMemMapKiB, int maxLineBufferCount, long minTimeGapMillis) {
				this.handle = handle;
				this.maxMemMapBytes = maxMemMapKiB * 1024L;
				this.maxLineBufferCount = maxLineBufferCount;
				this.minTimeGapMillis = minTimeGapMillis;
				this.lastFedObserverEmil = System.currentTimeMillis() - minTimeGapMillis;
				Charset cs = charset != null ? charset : StandardCharsets.UTF_8;
				this.decoder = cs.newDecoder();
				this.observer = observer;
				this.dirPath = path.getParent();
				this.fnamePath = path.getFileName();
			}
			public boolean fileExists() {
				return absFilePath().toFile().exists();
			}
			public boolean readyToFeedObserver() {
				return System.currentTimeMillis() - lastFedObserverEmil >= minTimeGapMillis;
			}
			public void feedObserver(Kind<Path> kind) {
				try {
					if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
						feedObserverNewly();
					} else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
						close();
						message = "file not found";
						observer.update(FileState.DOES_NOT_EXIST, null, message);
					} else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
						if (channel == null) {
							feedObserverNewly();
						} else {
							final long fileSize = channel.size();
							FileState fileState;
							boolean zeroLenAsWell;
							if (fileSize < lastPos) {
								message = "smaller";
								reset();
								fileState = FileState.RESET;
								zeroLenAsWell = true;
							} else {
								message = "continued";
								fileState = FileState.CONTINUED;
								zeroLenAsWell = false;
							}
							long pos = Math.max(lastPos, fileSize - maxMemMapBytes);
							long size = fileSize - pos;
							doFeedObserver(fileState, pos, size, zeroLenAsWell);
						}
					} else {
						System.out.println("Unknown kind: "+kind);
					}
				} catch (IOException e) {
					e.printStackTrace();
					this.message = "error: "+e.getMessage();
					observer.update(FileState.ERROR, null, message);
					channel = null;
				} finally {
					lastFedObserverEmil = System.currentTimeMillis();
				}
			}
			private void feedObserverNewly() throws IOException {
				open();
				final long fileSize = channel.size();
				long pos = Math.max(0L, fileSize - maxMemMapBytes);
				long size = fileSize - pos;
				doFeedObserver(FileState.RESET, pos, size, true);
			}
			private void doFeedObserver(FileState fileState, long pos, long size, boolean zeroLenAsWell) throws IOException {
				this.lastPos = pos + size;
				if (size == 0L) {
					if (zeroLenAsWell) {
						observer.update(fileState, Collections.emptyList(), message);
					}
				} else {
					final LinkedList<TailFileObserver.Line> lines = new LinkedList<>();
					final MappedByteBuffer map = channel.map(MapMode.READ_ONLY, pos, size);
					final CharBuffer charbuf = decoder.decode(map);
					while (charbuf.hasRemaining()) {
						final char c = charbuf.get();
						switch (c) {
						case '\n':
							wasLF = true;
							addline(lines, lineBld);
							break;
						case '\r':
							if (wasLF) {
								wasLF = false;
							} else {
								addline(lines, lineBld);
							}
							break;
						default:
							lineBld.append(c);
							wasLF = false;
							break;
						}
					}
					observer.update(fileState, lines, message);
				}
			}
			private void addline(final LinkedList<TailFileObserver.Line> lines, final StringBuilder lineBld) {
				lines.add(new TailFileObserver.Line(lineNo++, lineBld.toString()));
				if (lines.size() > maxLineBufferCount) {
					lines.removeFirst();
				}
				lineBld.setLength(0);
			}
			private void open() throws IOException {
				close(); // Just to be sure. We might not have done this yet ..
				channel = (FileChannel) Files.newByteChannel(absFilePath(), StandardOpenOption.READ);
				System.out.println("Opened file handle for "+absFilePath());
				this.message = "opened";
			}
			private Path absFilePath() {
				return dirPath.resolve(fnamePath);
			}
			public void close() {
				if (channel != null) {
					reset();
					try {
						channel.close();
						System.out.println("Closed file handle for "+absFilePath());
						message = "closed";
					} catch (IOException e) {
						message = "Could not close: "+e;
					}
					channel = null;
				}
			}
			private void reset() {
				lastPos = 0L;
				lineNo = 0;
				lineBld.setLength(0);
				wasLF = false;
			}
		}
		private static final class WatchedDir {
			public final WatchKey watchKey;
			public final Set<Long> handles = new HashSet<>();
			public WatchedDir(WatchKey watchKey) {
				this.watchKey = watchKey;
			}
			public void unwatch(Map<Long, TailWatchedFile> handle2twf) {
				if (watchKey != null) {
					watchKey.cancel();
				}
				for (Long handle: handles) {
					final TailWatchedFile twf = handle2twf.get(handle);
					if (twf != null) {
						twf.close();
					}
					
				}
			}
		}
		private enum PendingHandleAction {
			WATCH,
			UNWATCH
		}
		private static final class PendingObserverFodder {
			private final Map<Long, Kind<Path>> handleToFileWatcherKind = new LinkedHashMap<>();
			public Set<Entry<Long, Kind<Path>>> entrySet() {
				return handleToFileWatcherKind.entrySet();
			}
			public void put(final Long handle, Kind<Path> newKind) {
				if (handle == null || newKind == null) {
					return;
				}
				final Kind<Path> oldKind = handleToFileWatcherKind.get(handle);
				// nasty if-then-else-whatever with the prios: 1: DELETE, 2: CREATE, 3: MODIFY
				if (newKind == StandardWatchEventKinds.ENTRY_DELETE
						&& oldKind != StandardWatchEventKinds.ENTRY_DELETE) {
					handleToFileWatcherKind.put(handle, StandardWatchEventKinds.ENTRY_DELETE);
				} else if (newKind == StandardWatchEventKinds.ENTRY_CREATE
						&& oldKind != StandardWatchEventKinds.ENTRY_CREATE
						&& oldKind != StandardWatchEventKinds.ENTRY_DELETE) {
					handleToFileWatcherKind.put(handle, StandardWatchEventKinds.ENTRY_CREATE);
				} else if (newKind == StandardWatchEventKinds.ENTRY_MODIFY
						&& oldKind != StandardWatchEventKinds.ENTRY_MODIFY
						&& oldKind != StandardWatchEventKinds.ENTRY_CREATE
						&& oldKind != StandardWatchEventKinds.ENTRY_DELETE) {
					handleToFileWatcherKind.put(handle, StandardWatchEventKinds.ENTRY_MODIFY);
				}
			}
			public void remove(final Long handle) {
				if (handle == null) {
					return;
				}
				handleToFileWatcherKind.remove(handle);
			}
		}
	}
}
