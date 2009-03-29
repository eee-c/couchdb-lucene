package com.github.rnewson.couchdb.lucene;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.de.GermanAnalyzer;
import org.apache.lucene.analysis.el.GreekAnalyzer;
import org.apache.lucene.analysis.fr.FrenchAnalyzer;
import org.apache.lucene.analysis.nl.DutchAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.nutch.analysis.lang.LanguageIdentifier;

public final class LanguageIdentifyingAnalyzer extends Analyzer {

	private final Map<String, Analyzer> analyzerMap = new HashMap<String, Analyzer>();

	private final Analyzer defaultAnalyzer = new StandardAnalyzer();

	private final int limit = 200;

	public LanguageIdentifyingAnalyzer() {
		analyzerMap.put("de", new GermanAnalyzer());
		analyzerMap.put("nl", new DutchAnalyzer());
		analyzerMap.put("fr", new FrenchAnalyzer());
		analyzerMap.put("el", new GreekAnalyzer());
	}

	public void addAnalyzer(final String language, final Analyzer analyzer) {
		analyzerMap.put(language, analyzer);
	}

	@Override
	public TokenStream reusableTokenStream(final String fieldName, Reader reader) throws IOException {
		if (!reader.markSupported())
			reader = new BufferedReader(reader);

		final String lang = identify(reader);

		Analyzer analyzer = analyzerMap.get(lang);
		if (analyzer == null) {
			analyzer = defaultAnalyzer;
		}

		return analyzer.reusableTokenStream(fieldName, reader);
	}

	private String identify(final Reader reader) throws IOException {
		assert reader.markSupported();

		reader.mark(limit);
		final char[] buf = new char[limit];
		final int read = reader.read(buf);
		final String result = LanguageIdentifier.identifyLanguage(new String(buf, 0, read));
		reader.reset();

		return result;
	}

	@Override
	public TokenStream tokenStream(final String fieldName, Reader reader) {
		throw new UnsupportedOperationException();
	}

}
