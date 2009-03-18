package org.apache.couchdb.lucene;

import static java.lang.Math.min;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FieldSelector;
import org.apache.lucene.document.MapFieldSelector;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.search.CachingWrapperFilter;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermsFilter;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldDocs;

public final class SearchRequest {

	private static final FieldSelector FS = new MapFieldSelector(new String[] { Config.ID });

	private static final Database DB = new Database(Config.DB_URL);

	private final String dbname;

	private final Query q;

	private final Filter filter;

	private final Sort sort;

	private final int skip;

	private final int limit;

	private final boolean debug;

	private final boolean include_docs;

	private final boolean rewrite_query;

	private final String ifNoneMatch;

	private static final Map<String, Filter> DB_FILTERS = new HashMap<String, Filter>();

	public SearchRequest(final JSONObject obj) throws ParseException {
		final JSONObject headers = obj.getJSONObject("headers");
		final JSONObject info = obj.getJSONObject("info");
		final JSONObject query = obj.getJSONObject("query");

		this.ifNoneMatch = headers.optString("If-None-Match");
		this.dbname = info.getString("db_name");
		this.skip = query.optInt("skip", 0);
		this.limit = query.optInt("limit", 25);
		this.debug = query.optBoolean("debug", false);
		this.include_docs = query.optBoolean("include_docs", false);
		this.rewrite_query = query.optBoolean("rewrite", false);

		// Parse query.
		this.q = Config.QP.parse(query.getString("q"));

		// Filter by database.
		Filter filter = DB_FILTERS.get(this.dbname);
		if (filter == null) {
			filter = new TermsFilter();
			((TermsFilter) filter).addTerm(new Term(Config.DB, this.dbname));
			DB_FILTERS.put(this.dbname, new CachingWrapperFilter(filter));
		}

		this.filter = filter;

		// Parse sort order.
		final String sort = query.optString("sort", null);
		if (sort == null) {
			this.sort = null;
		} else {
			final String[] split = sort.split(",");
			final SortField[] sort_fields = new SortField[split.length];
			for (int i = 0; i < split.length; i++) {
				switch (split[i].charAt(0)) {
				case '/':
					sort_fields[i] = new SortField(split[i].substring(1));
					break;
				case '\\':
					sort_fields[i] = new SortField(split[i].substring(1), true);
					break;
				default:
					sort_fields[i] = new SortField(split[i]);
					break;
				}
			}

			if (sort_fields.length == 1) {
				// Let Lucene add doc as secondary sort order.
				this.sort = new Sort(sort_fields[0].getField(), sort_fields[0].getReverse());
			} else {
				this.sort = new Sort(sort_fields);
			}
		}
	}

	public String execute(final IndexSearcher searcher) throws IOException {
		// Decline requests over MAX_LIMIT.
		if (limit > Config.MAX_LIMIT) {
			return "{\"code\":400,\"body\":\"max limit was exceeded.\"}";
		}
		// Return "304 - Not Modified" if etag matches.
		final String etag = getETag(searcher);
		if (etag.equals(this.ifNoneMatch)) {
			return "{\"code\":304}";
		}

		final JSONObject json = new JSONObject();
		json.put("q", q.toString(Config.DEFAULT_FIELD));
		json.put("etag", etag);

		if (rewrite_query) {
			final Query rewritten_q = q.rewrite(searcher.getIndexReader());
			json.put("rewritten_q", rewritten_q.toString(Config.DEFAULT_FIELD));
			final JSONObject freqs = new JSONObject();

			final Set terms = new HashSet();
			rewritten_q.extractTerms(terms);
			for (final Object term : terms) {
				final int freq = searcher.docFreq((Term) term);
				freqs.put(term, freq);
			}
			json.put("freqs", freqs);
		} else {
			// Perform search.
			final TopDocs td;
			final StopWatch stopWatch = new StopWatch();
			if (sort == null) {
				td = searcher.search(q, filter, skip + limit);
			} else {
				td = searcher.search(q, filter, skip + limit, sort);
			}
			stopWatch.lap("search");
			// Fetch matches (if any).
			final int max = min(td.totalHits - skip, limit);
			final JSONArray rows = new JSONArray();
			final String[] fetch_ids = new String[max];
			for (int i = skip; i < skip + max; i++) {
				final Document doc = searcher.doc(td.scoreDocs[i].doc, FS);
				final JSONObject obj = new JSONObject();
				// Include basic details.
				obj.put("_id", doc.get(Config.ID));
				obj.put("score", td.scoreDocs[i].score);
				// Include sort order (if any).
				if (td instanceof TopFieldDocs) {
					final FieldDoc fd = (FieldDoc) ((TopFieldDocs) td).scoreDocs[i];
					obj.put("sort_order", fd.fields);
				}
				// Fetch document (if requested).
				if (include_docs) {
					fetch_ids[i - skip] = doc.get(Config.ID);
				}
				rows.add(obj);
			}
			// Fetch documents (if requested).
			if (include_docs) {
				final JSONArray fetched_docs = DB.getDocs(dbname, fetch_ids).getJSONArray("rows");
				for (int i = 0; i < max; i++) {
					rows.getJSONObject(i).put("doc", fetched_docs.getJSONObject(i).getJSONObject("doc"));
				}
			}
			stopWatch.lap("fetch");

			json.put("skip", skip);
			json.put("limit", limit);
			json.put("total_rows", td.totalHits);
			json.put("search_duration", stopWatch.getElapsed("search"));
			json.put("fetch_duration", stopWatch.getElapsed("fetch"));
			// Include sort info (if requested).
			if (td instanceof TopFieldDocs) {
				json.put("sort_order", toString(((TopFieldDocs) td).fields));
			}
			json.put("rows", rows);
		}

		final JSONObject result = new JSONObject();
		result.put("code", 200);

		final JSONObject headers = new JSONObject();
		headers.put("Cache-Control", "max-age=" + Config.TIME_THRESHOLD);
		// Results can't change unless the IndexReader does.
		headers.put("ETag", etag);
		result.put("headers", headers);

		if (debug) {
			result.put("body", String.format("<pre>%s</pre>", StringEscapeUtils.escapeHtml(json.toString(2))));
		} else {
			result.put("json", json);
		}

		return result.toString();
	}

	private String getETag(final IndexSearcher searcher) {
		return Long.toHexString(searcher.getIndexReader().getVersion());
	}

	private String toString(final SortField[] sortFields) {
		final JSONArray result = new JSONArray();
		for (final SortField field : sortFields) {
			final JSONObject col = new JSONObject();
			col.element("field", field.getField());
			col.element("reverse", field.getReverse());

			final String type;
			switch (field.getType()) {
			case SortField.DOC:
				type = "doc";
				break;
			case SortField.SCORE:
				type = "score";
				break;
			case SortField.INT:
				type = "int";
				break;
			case SortField.LONG:
				type = "long";
				break;
			case SortField.BYTE:
				type = "byte";
				break;
			case SortField.CUSTOM:
				type = "custom";
				break;
			case SortField.DOUBLE:
				type = "double";
				break;
			case SortField.FLOAT:
				type = "float";
				break;
			case SortField.SHORT:
				type = "short";
				break;
			case SortField.STRING:
				type = "string";
				break;
			default:
				type = "unknown";
				break;
			}
			col.element("type", type);
			result.add(col);
		}
		return result.toString();
	}

}
