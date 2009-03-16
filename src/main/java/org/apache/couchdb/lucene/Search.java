package org.apache.couchdb.lucene;

import java.io.IOException;
import java.util.Scanner;

import net.sf.json.JSONException;
import net.sf.json.JSONObject;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;

/**
 * Search entry point.
 */
public final class Search {

	private static class Stats {

		private long count = 0;
		private long duration = 0;
		private long max = 0;

		private void add(final long duration) {
			this.count++;
			this.duration += duration;
			this.max = Math.max(this.max, duration);
		}

		private void addTo(final JSONObject obj) {
			obj.put("search_count", count);
			obj.put("mean", count == 0 ? 0 : duration / count);
			obj.put("max", max);
		}

	}

	private static final Stats STATS = new Stats();

	public static void main(final String[] args) {
		try {
			IndexReader reader = null;
			IndexSearcher searcher = null;

			final Scanner scanner = new Scanner(System.in);
			while (scanner.hasNextLine()) {
				if (reader == null) {
					// Open a reader and searcher if index exists.
					if (IndexReader.indexExists(Config.INDEX_DIR)) {
						reader = IndexReader.open(NIOFSDirectory.getDirectory(Config.INDEX_DIR), true);
						searcher = new IndexSearcher(reader);
					}
				}

				final String line = scanner.nextLine();

				// Process search request if index exists.
				if (searcher == null) {
					System.out.println(Utils.error(503, "couchdb-lucene not available."));
					continue;
				}

				final JSONObject obj;
				try {
					obj = JSONObject.fromObject(line);
				} catch (final JSONException e) {
					System.out.println(Utils.error(400, "invalid JSON."));
					continue;
				}

				if (!obj.has("query")) {
					System.out.println(Utils.error(400, "No query found in request."));
					continue;
				}

				final JSONObject query = obj.getJSONObject("query");

				final boolean reopen = !"ok".equals(query.optString("stale", "not-ok"));

				// Refresh reader and searcher if necessary.
				if (reader != null && reopen) {
					final IndexReader newReader = reader.reopen();
					if (reader != newReader) {
						Log.outlog("Lucene index was updated, reopening searcher.");
						final IndexReader oldReader = reader;
						reader = newReader;
						searcher = new IndexSearcher(reader);
						oldReader.close();
					}
				}

				try {
					// A query.
					if (query.has("q")) {
						final StopWatch watch = new StopWatch();
						final SearchRequest request = new SearchRequest(obj);
						final String result = request.execute(searcher);
						System.out.println(result);
						watch.lap("search");
						STATS.add(watch.getElapsed("search"));
						continue;
					}
					// info.
					if (query.keySet().isEmpty()) {
						final JSONObject json = new JSONObject();
						json.put("doc_count", reader.numDocs());
						json.put("doc_del_count", reader.numDeletedDocs());
						json.put("disk_size", size(reader.directory()));
						STATS.addTo(json);
						reader.directory();

						final JSONObject info = new JSONObject();
						info.put("code", 200);
						info.put("json", json);
						final JSONObject headers = new JSONObject();
						headers.put("Content-Type", "text/plain");
						info.put("headers", headers);

						System.out.println(info);
					}
				} catch (final Exception e) {
					System.out.println(Utils.error(400, e.getMessage()));
				}

				System.out.println(Utils.error(400, "Bad request."));
			}
			if (reader != null) {
				reader.close();
			}
		} catch (final Exception e) {
			System.out.println(Utils.error(500, e.getMessage()));
		}
	}

	private static long size(final Directory dir) throws IOException {
		long result = 0;
		for (final String name : dir.list()) {
			result += dir.fileLength(name);
		}
		return result;
	}

}
