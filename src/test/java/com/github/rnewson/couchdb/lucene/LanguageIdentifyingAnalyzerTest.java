package com.github.rnewson.couchdb.lucene;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.StringReader;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.junit.Test;

public class LanguageIdentifyingAnalyzerTest {

	@Test
	public void testGerman() throws Exception {
		final Analyzer a = new LanguageIdentifyingAnalyzer();
		final TokenStream stream = a.reusableTokenStream("german", new StringReader(
				"dringliche und wichtige Fragen zur Reaktion der Union"));
		assertThat(stream.next().term(), is("dringlich"));
	}

}
