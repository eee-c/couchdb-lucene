package com.github.rnewson.couchdb.lucene;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.LowerCaseTokenizer;
import org.apache.lucene.analysis.PorterStemFilter;

import java.io.Reader;

class MyAnalyzer extends Analyzer {
  public final TokenStream tokenStream(String fieldName, Reader reader) {
    return new PorterStemFilter(new LowerCaseTokenizer(reader));
  }
}
