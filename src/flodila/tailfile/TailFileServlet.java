package flodila.tailfile;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.CloseReason;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;

/**
 * The web part of the tail -f servlet
 */
public final class TailFileServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private String servletName;
	private int maxLines;

	// ----------------------------------------------------
	// HttpServlet
	//
	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		this.servletName = config.getServletName();
		
		Integer maxLineBufferCount = servletIntegerInitParam(config, "maxLineBufferCount");
		if (maxLineBufferCount == null) {
			maxLineBufferCount = TailFileWatcher.DEFAULT_MAX_LINES;
		}
		this.maxLines = maxLineBufferCount.intValue();
		
		TailFileWatcherListener.tailFileServletName2FileName(config.getServletContext()).put(servletName,
				new TailFileConfig(
						config.getInitParameter("path"),
						config.getInitParameter("charset"),
						servletIntegerInitParam(config, "maxMemMapKiB"),
						maxLineBufferCount,
						servletIntegerInitParam(config, "minTimeGapMillis")));
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		TailFileConfig tailFileConfig = TailFileWatcherListener.tailFileServletName2FileName(getServletContext()).get(servletName);
		res.setContentType("text/html");
		res.setCharacterEncoding(StandardCharsets.UTF_8.name());
		final PrintWriter w = res.getWriter();
		w.println("<!DOCTYPE html>");
		w.println("<html>");
		{
			w.println("<head>");
			{
				w.println("<style>");
				{
					w.println("* { font-family: monospace; }");
					w.println("#header { position: absolute; height: 130px; background-color: #F8F8F8; top: 0; left: 0; right: 0; padding: 0 10px; border-bottom: 1px solid #E0E0E0; }");
					w.println("#lines { position: absolute; top: 131px; bottom: 0; left: 0; right: 0; padding: 0 10px; overflow-y: scroll; }");
					w.println("#lines div { margin-left: 100px; text-indent: -100px; white-space: pre-wrap; }");
					w.println("#togglemode { position: absolute; width: 50px; height: 50px; bottom: 10px; right: 10px; font-size: 20px; font-weight: bold; }");
					w.println("#mode { color: blue; }");
				}
				w.println("</style>");
				w.println("<script>");
				{
					w.println("(function(){");
					w.println("  var LINEBUFF_POWER = "+maxLines+";");
					w.println("  var TAIL_DISPLAY_POWER = 128;");
					w.println("  var MODE_TAIL_F = 'tail -f';");
					w.println("  var MODE_TAIL_N = 'tail -n '+LINEBUFF_POWER;");
					w.println("  var mode = MODE_TAIL_F;");
					w.println("  var linebuff = [];");
					w.println("  var notContinuedFlag;");
					w.println("  var socke = new WebSocket('ws://'+window.location.hostname+':'+window.location.port+'"
							+ req.getContextPath( ) + "/tailsock/" + escUrl(servletName) + "');");
					w.println("  socke.onerror = function(error) {");
					w.println("    console.error('Web Socket error', error);");
					w.println("    var messageEl = document.getElementById('message');");
					w.println("    empty(messageEl);");
					w.println("    messageEl.appendChild(document.createTextNode('Web Socket error: '+error));");
					w.println("  };");
					w.println("  socke.onmessage = function(event) {");
					w.println("    if (event.data) {");
					w.println("      var msg = JSON.parse(event.data);");
					w.println("      if (msg.state !== 'CONTINUED') {");
					w.println("        cleanLinebuff();");
					w.println("        notContinuedFlag = true;");
					w.println("      }");
					w.println("      var messageEl = document.getElementById('message');");
					w.println("      empty(messageEl);");
					w.println("      messageEl.appendChild(document.createTextNode(msg.message));");
					w.println("      linebuffAdd(msg.lines);");
					w.println("      if (mode === MODE_TAIL_F) {");
					w.println("        displayLinebuff(TAIL_DISPLAY_POWER);");
					w.println("      }");
					w.println("    }");
					w.println("  };");
					w.println("  function cleanLinebuff() {");
					w.println("    linebuff = [];");
					w.println("  }");
					w.println("  function linebuffAdd(lines) {");
					w.println("    if (!lines) return;");
					w.println("    linebuff = linebuff.concat(lines);");
					w.println("    linebuff.sort(function(l1, l2) {");
					w.println("      if (!l1 && !l2) return 0;");
					w.println("      if (!l1) return -1;");
					w.println("      if (!l2) return 1;");
					w.println("      if (!l1.n && !l2.n) return 0;");
					w.println("      if (!l1.n) return -1;");
					w.println("      if (!l2.n) return 1;");
					w.println("      return l1.n - l2.n;");
					w.println("    });");
					w.println("    while (linebuff.length > LINEBUFF_POWER) linebuff.shift();");
					w.println("  }");
					w.println("  function displayLinebuff(n) {");
					w.println("    var currNo, toBeRemovedEl;");
					w.println("    var bufflen = linebuff.length;");
					w.println("    var displayPower = (!n || n > bufflen) ? bufflen : n;");
					w.println("    var start = bufflen - displayPower;");
					w.println("    var linesEl = document.getElementById('lines');");
					w.println("    if (notContinuedFlag) {");
					w.println("      empty(linesEl);");
					w.println("      notContinuedFlag = false;");
					w.println("    }");
					w.println("    var currLineEl = linesEl.firstChild;"); // null means end reached
					w.println("    for (var i=start; i<bufflen; i++) {");
					w.println("      var buffLine = linebuff[i];");
					w.println("      var buffNo = buffLine.n;");
					w.println("      while (typeof (currNo = elIdNo(currLineEl)) !== 'undefined' && currNo < buffNo) {");
					w.println("        toBeRemovedEl = currLineEl;");
					w.println("        currLineEl = currLineEl.nextSibling;");
					w.println("        linesEl.removeChild(toBeRemovedEl);");
					w.println("      }");
					w.println("      if (!currLineEl) {");
					w.println("        linesEl.appendChild(newLineEl(buffLine));");
					w.println("      } else if (currNo > buffNo) {");
					w.println("        linesEl.insertBefore(newLineEl(buffLine), currLineEl);");
					w.println("      } else {");
					w.println("        currLineEl = currLineEl.nextSibling;");
					w.println("      }");
					w.println("    }");
					w.println("    while (currLineEl) {");
					w.println("      toBeRemovedEl = currLineEl;");
					w.println("      currLineEl = currLineEl.nextSibling;");
					w.println("      linesEl.removeChild(toBeRemovedEl);");
					w.println("    }");
					w.println("    linesEl.scrollTo(0, linesEl.scrollHeight);");
					w.println("    function elIdNo(el) {");
					w.println("      if (!el) return undefined;");
					w.println("      return parseInt(el.getAttribute('id').substring(3), 10);");
					w.println("    }");
					w.println("    function newLineEl(buffLine) {");
					w.println("      var lineDivEl = document.createElement('div');");
					w.println("      lineDivEl.setAttribute('id', 'ld_'+buffLine.n);");
					w.println("      if (buffLine.t) {");
					w.println("        lineDivEl.appendChild(document.createTextNode(buffLine.t));");
					w.println("      } else {");
					w.println("        lineDivEl.appendChild(document.createElement('br'));");
					w.println("      }");
					w.println("      return lineDivEl;");
					w.println("    }");
					w.println("  }");
					w.println("  function empty(el) {");
					w.println("    var i;");
					w.println("    var children = el.childNodes;");
					w.println("    var toBeRemoved = [];");
					w.println("    for (i=0; i<children.length; i++) {");
					w.println("      toBeRemoved.push(children.item(i));");
					w.println("    }");
					w.println("    for (i=0; i<toBeRemoved.length; i++) {");
					w.println("      el.removeChild(toBeRemoved[i]);");
					w.println("    }");
					w.println("  }");
					w.println("  document.onreadystatechange = function() {");
					w.println("    if (document.readyState === 'complete') {");
					w.println("      document.getElementById('togglemode').addEventListener('click', function() {");
					w.println("        if (mode === MODE_TAIL_F) {");
					w.println("          mode = MODE_TAIL_N;");
					w.println("          displayLinebuff(LINEBUFF_POWER);");
					w.println("        } else {;");
					w.println("          mode = MODE_TAIL_F;");
					w.println("          displayLinebuff(TAIL_DISPLAY_POWER);");
					w.println("        }");
					w.println("        var modeEl = document.getElementById('mode');");
					w.println("        empty(modeEl);");
					w.println("        modeEl.appendChild(document.createTextNode(mode));");
					w.println("      });");
					w.println("    }");
					w.println("  };");
					w.println("})();");
				}
				w.println("</script>");
			}
			w.println("</head>");
			w.println("<body>");
			{
				w.println("<div id=\"header\">");
				w.println("<h1>"+escHt(this.servletName)+"</h1>");
				String charsetSuffix = tailFileConfig.charset != null ? escHt(" (" + tailFileConfig.charset.name() + ")") : "";
				w.println("<h2><span id=\"mode\">tail -f</span> "+escHt(""+tailFileConfig.file)+charsetSuffix+"</h2>");
				w.println("<div id=\"message\">Initializing ...</div>");
				w.println("<button id=\"togglemode\">&#x25CF;</button>");
				w.println("</div>");
				w.println("<div id=\"lines\"></div>");
			}
			w.println("</body>");
		}
		w.println("</html>");
	}

	// ----------------------------------------------------
	// extract
	//
	private static Integer servletIntegerInitParam(ServletConfig config, String name) {
		final String str = config.getInitParameter(name);
		return str != null ? Integer.valueOf(str.trim()) : null;
	}
	private static String escHt(final String s) {
		if (s == null) {
			return "";
		}
		final int len = s.length();
		final StringBuilder sb = new StringBuilder(len + (len / 8));
		for (int i=0; i<len; i++) {
			final char c = s.charAt(i);
			switch (c) {
			case '&':
				sb.append("&amp;");
				break;
			case '<':
				sb.append("&lt;");
				break;
			case '>':
				sb.append("&gt;");
				break;
			case '\"':
				sb.append("&quot;");
				break;
			default:
				sb.append(c);
				break;
			}
		}
		return sb.toString();
	}
	private static String escUrl(final String s) {
		try {
			return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}

	// ----------------------------------------------------
	// Tail File Watcher
	//
	@WebListener
	public static final class TailFileWatcherListener implements ServletContextListener {
		private static final String TFW_SERVLET_ATTRIBUTE = TailFileWatcher.class.getName();
		private static final String S2F_SERVLET_ATTRIBUTE = "tailFileServletName2FileName";
		@Override
		public void contextInitialized(ServletContextEvent sce) {
			final ServletContext sctx = sce.getServletContext();
			sctx.setAttribute(TFW_SERVLET_ATTRIBUTE, new TailFileWatcher());
			sctx.setAttribute(S2F_SERVLET_ATTRIBUTE, new ConcurrentHashMap<String, TailFileConfig>());
			
			// so ugly. Look away! Or give me a better solution!
			ServerContainer sc = (ServerContainer) sce.getServletContext().getAttribute("javax.websocket.server.ServerContainer");
			try {
				final ServerEndpointConfig endpointConfig = ServerEndpointConfig.Builder.create(TailsockEndpoint.class, "/tailsock/{servletName}")
						.build();
				endpointConfig.getUserProperties().put("sctx", sctx);
				sc.addEndpoint(endpointConfig);
			} catch (DeploymentException e) {
				e.printStackTrace();
			}
		}
		@Override
		public void contextDestroyed(ServletContextEvent sce) {
			final ServletContext sctx = sce.getServletContext();
			tailFileWatcher(sctx).shutdown();
			sctx.removeAttribute(TFW_SERVLET_ATTRIBUTE);
			sctx.removeAttribute(S2F_SERVLET_ATTRIBUTE);
		}
		public static TailFileWatcher tailFileWatcher(ServletContext sctx) {
			return (TailFileWatcher) sctx.getAttribute(TFW_SERVLET_ATTRIBUTE);
		}
		@SuppressWarnings("unchecked")
		public static Map<String, TailFileConfig> tailFileServletName2FileName(ServletContext sctx) {
			return (Map<String, TailFileConfig>) sctx.getAttribute(S2F_SERVLET_ATTRIBUTE);
		}
	}

	// ----------------------------------------------------
	// Web Socket Endpoint
	//
	public static final class TailsockEndpoint extends Endpoint {
		private ServletContext sctx;
		private final Map<String, Long> session2handle = new HashMap<>();
		@Override
		public void onOpen(Session session, EndpointConfig config) {
			System.out.println("Somebody joined :-)");
			final Map<String, Object> userProperties = config.getUserProperties();
			this.sctx = (ServletContext) userProperties.get("sctx");
			final Map<String, String> pathParameters = session.getPathParameters();
			final String servletName = pathParameters.get("servletName");
			final TailFileConfig tailFileConfig = TailFileWatcherListener.tailFileServletName2FileName(sctx).get(servletName);
			final Long handle = TailFileWatcherListener.tailFileWatcher(sctx).watch(
					tailFileConfig.file,
					tailFileConfig.charset,
					newTailFileObserver(session),
					tailFileConfig.maxMemMapKiB,
					tailFileConfig.maxLineBufferCount,
					tailFileConfig.minTimeGapMillis);
			this.session2handle.put(session.getId(), handle);
		}
		private TailFileObserver newTailFileObserver(Session session) {
			return new TailFileObserver() {
				private volatile TryAgainer tryAgainer = new TryAgainer();
				private static final char JSON_OBJ_START = '{';
				private static final char JSON_OBJ_END = '}';
				private static final char JSON_QUOT = '\"';
				private static final char JSON_COLON = ':';
				private static final char JSON_COMMA = ',';
				private static final char JSON_ARRAY_START = '[';
				private static final char JSON_ARRAY_END = ']';
				@Override
				public void update(FileState state, List<Line> newLines, String message) {
					if (tryAgainer.pendingCount > 0) {
						newLines.addAll(0, tryAgainer.lines);
						state = tryAgainer.state.and(state);
						message = tryAgainer.message + " - " + message;
					}
					final StringBuilder jsonBld = new StringBuilder();
					jsonBld.append(JSON_OBJ_START);
					jsonBld.append(JSON_QUOT).append("state").append(JSON_QUOT).append(JSON_COLON).append(JSON_QUOT).append(escJson(""+state)).append(JSON_QUOT);
					jsonBld.append(JSON_COMMA);
					jsonBld.append(JSON_QUOT).append("message").append(JSON_QUOT).append(JSON_COLON).append(JSON_QUOT).append(escJson(message)).append(JSON_QUOT);
					if (newLines != null) {
						jsonBld.append(JSON_COMMA);
						jsonBld.append(JSON_QUOT).append("lines").append(JSON_QUOT).append(JSON_COLON).append(JSON_ARRAY_START);
						boolean cont = false;
						for (Line line : newLines) {
							if (cont) {
								jsonBld.append(JSON_COMMA);
							}
							jsonBld.append(JSON_OBJ_START);
							jsonBld.append(JSON_QUOT).append("n").append(JSON_QUOT).append(JSON_COLON).append(line.lineno);
							jsonBld.append(JSON_COMMA);
							jsonBld.append(JSON_QUOT).append("t").append(JSON_QUOT).append(JSON_COLON).append(JSON_QUOT).append(escJson(line.content)).append(JSON_QUOT);
							jsonBld.append(JSON_OBJ_END);
							cont = true;
						}
						jsonBld.append(JSON_ARRAY_END);
					}
					jsonBld.append(JSON_OBJ_END);
					final String json = jsonBld.toString();
					try {
						session.getAsyncRemote().sendText(json);
						tryAgainer.reset();
					} catch (IllegalStateException e) {
						System.out.println("Could not send. Will try again next time. "+tryAgainer.pendingCount);
						if (tryAgainer.pendingCount > 25) {
							tryAgainer.reset();
							e.printStackTrace();
						} else {
							tryAgainer.pendingCount++;
							tryAgainer.lines.addAll(newLines);
							tryAgainer.state = tryAgainer.state.and(state);
							tryAgainer.message = e.getMessage();
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			};
		}
		@Override
		public void onClose(Session session, CloseReason closeReason) {
			System.out.println("Somebody left :-(");
			final String sessionId = session.getId();
			final Long handle = this.session2handle.get(sessionId);
			if (handle != null) {
				TailFileWatcherListener.tailFileWatcher(sctx).unwatch(handle);
			}
			super.onClose(session, closeReason);
		}
		private static String escJson(final String s) {
			if (s == null) {
				return "";
			}
			final int len = s.length();
			final StringBuilder sb = new StringBuilder(len + (len / 16));
			for (int i=0; i<len; i++) {
				final char c = s.charAt(i);
				switch (c) {
				case '\n':
					sb.append("\\n");
					break;
				case '\t':
					sb.append("\\t");
					break;
				case '\r':
					sb.append("\\r");
					break;
				case '\b':
					sb.append("\\b");
					break;
				case '\f':
					sb.append("\\f");
					break;
				case '\\':
				case '/':
				case '\"':
					sb.append('\\');
					// no break on purpose
				default:
					sb.append(c);
					break;
				}
			}
			return sb.toString();
		}
	}

	// ----------------------------------------------------
	// TailFileConfig
	//
	private static final class TailFileConfig {
		public final File file;
		public final Charset charset;
		public final Integer maxMemMapKiB;
		public final Integer maxLineBufferCount;
		public final Integer minTimeGapMillis;
		public TailFileConfig(String path, String charset, Integer maxMemMapKiB, Integer maxLineBufferCount, Integer minTimeGapMillis) {
			this.file = new File(path);
			this.charset = charset != null ? Charset.forName(charset) : null;
			this.maxMemMapKiB = maxMemMapKiB;
			this.maxLineBufferCount = maxLineBufferCount;
			this.minTimeGapMillis = minTimeGapMillis;
		}
	}
	private static final class TryAgainer {
		public int pendingCount = 0;
		public TailFileObserver.FileState state = TailFileObserver.FileState.CONTINUED;
		public final List<TailFileObserver.Line> lines = new LinkedList<>();
		public String message = "err";
		public void reset() {
			pendingCount = 0;
			state = TailFileObserver.FileState.CONTINUED;
			lines.clear();
			message = "err";
		}
	}
}
