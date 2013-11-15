package at.ac.univie.mminf.luceneSKOS.skos.impl;

/**
 * Copyright 2010 Bernhard Haslhofer 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.SimpleAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.Version;

import at.ac.univie.mminf.luceneSKOS.skos.SKOS;
import at.ac.univie.mminf.luceneSKOS.skos.SKOSEngine;

import com.hp.hpl.jena.ontology.AnnotationProperty;
import com.hp.hpl.jena.ontology.ObjectProperty;
import com.hp.hpl.jena.rdf.model.Literal;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.ResIterator;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.hp.hpl.jena.update.GraphStore;
import com.hp.hpl.jena.update.GraphStoreFactory;
import com.hp.hpl.jena.update.UpdateAction;
import com.hp.hpl.jena.update.UpdateFactory;
import com.hp.hpl.jena.update.UpdateRequest;
import com.hp.hpl.jena.util.FileManager;
import com.hp.hpl.jena.vocabulary.RDF;

/**
 * A Lucene-backed SKOSEngine Implementation.
 * 
 * Each SKOS concept is stored/indexed as a Lucene document.
 * 
 * All labels are converted to lowercase
 */
public class SKOSEngineImpl implements SKOSEngine {
  
  /** Records the total number of matches */
  public static class AllDocCollector extends Collector {
    private final List<Integer> docs = new ArrayList<Integer>();
    private int base;
    
    @Override
    public boolean acceptsDocsOutOfOrder() {
      return true;
    }
    
    @Override
    public void collect(int doc) throws IOException {
      docs.add(doc + base);
    }
    
    public List<Integer> getDocs() {
      return docs;
    }
    
    @Override
    public void setNextReader(AtomicReaderContext context) throws IOException {
      base = context.docBase;
    }
    
    @Override
    public void setScorer(Scorer scorer) throws IOException {
      // not needed
    }
  }
  
  protected final Version matchVersion;
  
  /*
   * Static fields used in the Lucene Index
   */
  private static final String FIELD_URI = "uri";
  private static final String FIELD_PREF_LABEL = "pref";
  private static final String FIELD_ALT_LABEL = "alt";
  private static final String FIELD_HIDDEN_LABEL = "hidden";
  private static final String FIELD_BROADER = "broader";
  private static final String FIELD_NARROWER = "narrower";
  private static final String FIELD_BROADER_TRANSITIVE = "broaderTransitive";
  private static final String FIELD_NARROWER_TRANSITIVE = "narrowerTransitive";
  private static final String FIELD_RELATED = "related";
  //for EHRI skos
  private static final String FIELD_PREFMALE_LABEL = "prefMale";
  private static final String FIELD_PREFFEMALE_LABEL = "prefFemale";
  private static final String FIELD_PREFNEUTER_LABEL = "prefNeuter";
  private static final String FIELD_ALTMALE_LABEL = "altMale";
  private static final String FIELD_ALTFEMALE_LABEL = "altFemale";
  private static final String FIELD_ALTNEUTER_LABEL = "altNeuter";
  
  /**
   * The input SKOS model
   */
  private Model skosModel;
  
  /**
   * The location of the concept index
   */
  private Directory indexDir;
  
  /**
   * Provides access to the index
   */
  private IndexSearcher searcher;
  
  /**
   * The languages to be considered when returning labels.
   * 
   * If NULL, all languages are supported
   */
  private Set<String> languages;
  
  /**
   * The analyzer used during indexing of / querying for concepts
   * 
   * SimpleAnalyzer = LetterTokenizer + LowerCaseFilter
   */
  private final Analyzer analyzer;
  
  /**
   * This constructor loads the SKOS model from a given InputStream using the
   * given serialization language parameter, which must be either N3, RDF/XML,
   * or TURTLE.
   * 
   * @param inputStream
   *          the input stream
   * @param lang
   *          the serialization language
   * @throws IOException
   *           if the model cannot be loaded
   */
  public SKOSEngineImpl(final Version version, InputStream inputStream,
      String lang) throws IOException {
    
    if (!("N3".equals(lang) || "RDF/XML".equals(lang) || "TURTLE".equals(lang))) {
      throw new IOException("Invalid RDF serialization format");
    }
    
    matchVersion = version;
    
    analyzer = new SimpleAnalyzer(matchVersion);
    
    skosModel = ModelFactory.createDefaultModel();
    
    skosModel.read(inputStream, null, lang);
    
    indexDir = new RAMDirectory();
    
    entailSKOSModel();
    
    indexSKOSModel();
    
    searcher = new IndexSearcher(DirectoryReader.open(indexDir));
  }
  
  /**
   * Constructor for all label-languages
   * 
   * @param filenameOrURI
   *          the name of the skos file to be loaded
   * @throws IOException
   */
  public SKOSEngineImpl(final Version version, String filenameOrURI)
      throws IOException {
    this(version, filenameOrURI, (String[]) null);
  }
  
  /**
   * This constructor loads the SKOS model from a given filename or URI, starts
   * the indexing process and sets up the index searcher.
   * 
   * @param languages
   *          the languages to be considered
   * @param filenameOrURI
   * @throws IOException
   */
  public SKOSEngineImpl(final Version version, String filenameOrURI,
      String... languages) throws IOException {
    matchVersion = version;
    analyzer = new SimpleAnalyzer(matchVersion);
    
    String langSig = "";
    if (languages != null) {
      this.languages = new TreeSet<String>(Arrays.asList(languages));
      langSig = "-" + StringUtils.join(this.languages, ".");
    }
    
    String name = FilenameUtils.getName(filenameOrURI);
    File dir = new File("skosdata/" + name + langSig);
    indexDir = FSDirectory.open(dir);
    
    // TODO: Generate also if source file is modified
    if (!dir.isDirectory()) {
      // load the skos model from the given file
      FileManager fileManager = new FileManager();
      fileManager.addLocatorFile();
      fileManager.addLocatorURL();
      fileManager.addLocatorClassLoader(SKOSEngineImpl.class.getClassLoader());
      
      if (FilenameUtils.getExtension(filenameOrURI).equals("zip")) {
        fileManager.addLocatorZip(filenameOrURI);
        filenameOrURI = FilenameUtils.getBaseName(filenameOrURI);
      }
      
      skosModel = fileManager.loadModel(filenameOrURI);
      
      entailSKOSModel();
      
      indexSKOSModel();
    }
    
    searcher = new IndexSearcher(DirectoryReader.open(indexDir));
  }
  
  //extended to include EHRI extension
  private void entailSKOSModel() {
    GraphStore graphStore = GraphStoreFactory.create(skosModel) ;
    String sparqlQuery = StringUtils.join(new String[]{
        "PREFIX skos: <http://www.w3.org/2004/02/skos/core#>",
        "PREFIX skos-ehri: <http://data.ehri-project.eu/skos-extension#>",
        "PREFIX rdf:<http://www.w3.org/1999/02/22-rdf-syntax-ns#>",
        "INSERT { ?subject rdf:type skos:Concept }",
        "WHERE {",
          "{ ?subject skos:prefLabel ?text } UNION",
          "{ ?subject skos:altLabel ?text } UNION",
          "{ ?subject skos-ehri:prefMaleLabel ?text } UNION",
          "{ ?subject skos-ehri:prefFemaleLabel ?text } UNION",
          "{ ?subject skos-ehri:prefNeuterLabel ?text } UNION",
          "{ ?subject skos-ehri:altMaleLabel ?text } UNION",
          "{ ?subject skos-ehri:altFemaleLabel ?text } UNION",
          "{ ?subject skos-ehri:altNeuterLabel ?text } UNION",
          "{ ?subject skos:hiddenLabel ?text }",
         "}",
        }, "\n");
    UpdateRequest request = UpdateFactory.create(sparqlQuery);
    UpdateAction.execute(request, graphStore) ;
  }

  /**
   * Creates lucene documents from SKOS concept. In order to allow language
   * restrictions, one document per language is created.
   */
  private Document createDocumentsFromConcept(Resource skos_concept) {
    Document conceptDoc = new Document();
    
    String conceptURI = skos_concept.getURI();
    if (conceptURI == null) {
      System.err.println("Error when indexing concept NO_URI.");
      return null;
    }
    
    Field uriField = new Field(FIELD_URI, conceptURI, StringField.TYPE_STORED);
    conceptDoc.add(uriField);
    
    // store the preferred lexical labels
    indexAnnotation(skos_concept, conceptDoc, SKOS.prefLabel, FIELD_PREF_LABEL);
    
    // store the alternative lexical labels
    indexAnnotation(skos_concept, conceptDoc, SKOS.altLabel, FIELD_ALT_LABEL);
    
    // store the hidden lexical labels
    indexAnnotation(skos_concept, conceptDoc, SKOS.hiddenLabel,
        FIELD_HIDDEN_LABEL);
    
    // store lexical labels for EHRI extension
    indexAnnotation(skos_concept, conceptDoc, SKOS.prefMaleLabel, FIELD_PREFMALE_LABEL);
    indexAnnotation(skos_concept, conceptDoc, SKOS.prefFemaleLabel, FIELD_PREFFEMALE_LABEL);
    indexAnnotation(skos_concept, conceptDoc, SKOS.prefNeuterLabel, FIELD_PREFNEUTER_LABEL);
    indexAnnotation(skos_concept, conceptDoc, SKOS.altMaleLabel, FIELD_ALTMALE_LABEL);
    indexAnnotation(skos_concept, conceptDoc, SKOS.altFemaleLabel, FIELD_ALTFEMALE_LABEL);
    indexAnnotation(skos_concept, conceptDoc, SKOS.altNeuterLabel, FIELD_ALTNEUTER_LABEL);
    
    // store the URIs of the broader concepts
    indexObject(skos_concept, conceptDoc, SKOS.broader, FIELD_BROADER);
    
    // store the URIs of the broader transitive concepts
    indexObject(skos_concept, conceptDoc, SKOS.broaderTransitive,
        FIELD_BROADER_TRANSITIVE);
    
    // store the URIs of the narrower concepts
    indexObject(skos_concept, conceptDoc, SKOS.narrower, FIELD_NARROWER);
    
    // store the URIs of the narrower transitive concepts
    indexObject(skos_concept, conceptDoc, SKOS.narrowerTransitive,
        FIELD_NARROWER_TRANSITIVE);
    
    // store the URIs of the related concepts
    indexObject(skos_concept, conceptDoc, SKOS.related, FIELD_RELATED);
    
    return conceptDoc;
  }
  
  @Override
  public String[] getAltLabels(String conceptURI) throws IOException {
    return readConceptFieldValues(conceptURI, FIELD_ALT_LABEL);
  }
  
  
  @Override
  public String[] getAltMaleLabels(String conceptURI) throws IOException {
    return readConceptFieldValues(conceptURI, FIELD_ALTMALE_LABEL);
  }
    @Override
  public String[] getAltFemaleLabels(String conceptURI) throws IOException {
    return readConceptFieldValues(conceptURI, FIELD_ALTFEMALE_LABEL);
  }
  @Override
  public String[] getAltNeuterLabels(String conceptURI) throws IOException {
    return readConceptFieldValues(conceptURI, FIELD_ALTNEUTER_LABEL);
  }
  
  
  
  @Override
  public String[] getAltTerms(String label) throws IOException {
    List<String> result = new ArrayList<String>();
    
    // convert the query to lower-case
    String queryString = label.toLowerCase();
    
    try {
      String[] conceptURIs = getConcepts(queryString);
      
      for (String conceptURI : conceptURIs) {
        String[] altLabels = getAltLabels(conceptURI);
        if (altLabels != null) {
          for (String altLabel : altLabels) {
            result.add(altLabel);
          }
        }
      }
    } catch (Exception e) {
      System.err
          .println("Error when accessing SKOS Engine.\n" + e.getMessage());
    }
    
    return result.toArray(new String[result.size()]);
  }
  
  @Override
  public String[] getHiddenLabels(String conceptURI) throws IOException {
    return readConceptFieldValues(conceptURI, FIELD_HIDDEN_LABEL);
  }
  
  @Override
  public String[] getBroaderConcepts(String conceptURI) throws IOException {
    return readConceptFieldValues(conceptURI, FIELD_BROADER);
  }
  
  @Override
  public String[] getBroaderLabels(String conceptURI) throws IOException {
    return getLabels(conceptURI, FIELD_BROADER);
  }
  
  @Override
  public String[] getBroaderTransitiveConcepts(String conceptURI)
      throws IOException {
    return readConceptFieldValues(conceptURI, FIELD_BROADER_TRANSITIVE);
  }
  
  @Override
  public String[] getBroaderTransitiveLabels(String conceptURI)
      throws IOException {
    return getLabels(conceptURI, FIELD_BROADER_TRANSITIVE);
  }
  
  @Override
  public String[] getConcepts(String label) throws IOException {
    List<String> concepts = new ArrayList<String>();
    
    // convert the query to lower-case
    String queryString = label.toLowerCase();
    
    AllDocCollector collector = new AllDocCollector();
    
    DisjunctionMaxQuery query = new DisjunctionMaxQuery(0.0f);
    query.add(new TermQuery(new Term(FIELD_PREF_LABEL, queryString)));
    query.add(new TermQuery(new Term(FIELD_ALT_LABEL, queryString)));
    query.add(new TermQuery(new Term(FIELD_HIDDEN_LABEL, queryString)));
    query.add(new TermQuery(new Term(FIELD_PREFMALE_LABEL, queryString)));
    query.add(new TermQuery(new Term(FIELD_PREFFEMALE_LABEL, queryString)));
    query.add(new TermQuery(new Term(FIELD_PREFNEUTER_LABEL, queryString)));
    query.add(new TermQuery(new Term(FIELD_ALTMALE_LABEL, queryString)));
    query.add(new TermQuery(new Term(FIELD_ALTFEMALE_LABEL, queryString)));
    query.add(new TermQuery(new Term(FIELD_ALTNEUTER_LABEL, queryString)));
    searcher.search(query, collector);
    
    for (Integer hit : collector.getDocs()) {
      Document doc = searcher.doc(hit);
      String conceptURI = doc.getValues(FIELD_URI)[0];
      concepts.add(conceptURI);
    }
    
    return concepts.toArray(new String[concepts.size()]);
  }
  
  private String[] getLabels(String conceptURI, String field)
      throws IOException {
    List<String> labels = new ArrayList<String>();
    String[] concepts = readConceptFieldValues(conceptURI, field);
    
    for (String aConceptURI : concepts) {
      String[] prefLabels = getPrefLabels(aConceptURI);
      labels.addAll(Arrays.asList(prefLabels));
      
      String[] altLabels = getAltLabels(aConceptURI);
      labels.addAll(Arrays.asList(altLabels));
      
      //EHRI-skos extension
      String[] prefMaleLabels = getPrefMaleLabels(aConceptURI);
      labels.addAll(Arrays.asList(prefMaleLabels));
      String[] prefFemaleLabels = getPrefFemaleLabels(aConceptURI);
      labels.addAll(Arrays.asList(prefFemaleLabels));
      String[] prefNeuterLabels = getPrefNeuterLabels(aConceptURI);
      labels.addAll(Arrays.asList(prefNeuterLabels));
      String[] altMaleLabels = getAltMaleLabels(aConceptURI);
      labels.addAll(Arrays.asList(altMaleLabels));
      String[] altFemaleLabels = getAltFemaleLabels(aConceptURI);
      labels.addAll(Arrays.asList(altFemaleLabels));
      String[] altNeuterLabels = getAltNeuterLabels(aConceptURI);
      labels.addAll(Arrays.asList(altNeuterLabels));
      
    }
    
    return labels.toArray(new String[labels.size()]);
  }
  
  @Override
  public String[] getNarrowerConcepts(String conceptURI) throws IOException {
    return readConceptFieldValues(conceptURI, FIELD_NARROWER);
  }
  
  @Override
  public String[] getNarrowerLabels(String conceptURI) throws IOException {
    return getLabels(conceptURI, FIELD_NARROWER);
  }
  
  @Override
  public String[] getNarrowerTransitiveConcepts(String conceptURI)
      throws IOException {
    return readConceptFieldValues(conceptURI, FIELD_NARROWER_TRANSITIVE);
  }
  
  @Override
  public String[] getNarrowerTransitiveLabels(String conceptURI)
      throws IOException {
    return getLabels(conceptURI, FIELD_NARROWER_TRANSITIVE);
  }
  
  @Override
  public String[] getPrefLabels(String conceptURI) throws IOException {
    return readConceptFieldValues(conceptURI, FIELD_PREF_LABEL);
  }
  
  @Override
  public String[] getRelatedConcepts(String conceptURI) throws IOException {
    return readConceptFieldValues(conceptURI, FIELD_RELATED);
  }
  
  @Override
  public String[] getRelatedLabels(String conceptURI) throws IOException {
    return getLabels(conceptURI, FIELD_RELATED);
  }
  
  @Override
  public String[] getPrefMaleLabels(String conceptURI) throws IOException {
    return readConceptFieldValues(conceptURI, FIELD_PREFMALE_LABEL);
  }
  
  @Override
  public String[] getPrefFemaleLabels(String conceptURI) throws IOException {
    return readConceptFieldValues(conceptURI, FIELD_PREFFEMALE_LABEL);
  }
  
  @Override
  public String[] getPrefNeuterLabels(String conceptURI) throws IOException {
    return readConceptFieldValues(conceptURI, FIELD_PREFNEUTER_LABEL);
  }
  
  private void indexAnnotation(Resource skos_concept, Document conceptDoc,
      AnnotationProperty property, String field) {
    StmtIterator stmt_iter = skos_concept.listProperties(property);
    while (stmt_iter.hasNext()) {
      Literal labelLiteral = stmt_iter.nextStatement().getObject()
          .as(Literal.class);
      String label = labelLiteral.getLexicalForm();
      String labelLang = labelLiteral.getLanguage();
      
      if (this.languages != null && !this.languages.contains(labelLang)) {
        continue;
      }
      
      // converting label to lower-case
      label = label.toLowerCase();
      
      Field labelField = new Field(field, label, StringField.TYPE_STORED);
      
      conceptDoc.add(labelField);
    }
  }
  
  private void indexObject(Resource skos_concept, Document conceptDoc,
      ObjectProperty property, String field) {
    StmtIterator stmt_iter = skos_concept.listProperties(property);
    while (stmt_iter.hasNext()) {
      RDFNode concept = stmt_iter.nextStatement().getObject();
      
      if (!concept.canAs(Resource.class)) {
        System.err.println("Error when indexing relationship of concept "
            + skos_concept.getURI() + ".");
        continue;
      }
      
      Resource resource = concept.as(Resource.class);
      
      String uri = resource.getURI();
      if (uri == null) {
        System.err.println("Error when indexing relationship of concept "
            + skos_concept.getURI() + ".");
        continue;
      }
      
      Field conceptField = new Field(field, uri, StringField.TYPE_STORED);
      
      conceptDoc.add(conceptField);
    }
  }
  
  /**
   * Creates the synonym index
   * 
   * @throws IOException
   */
  private void indexSKOSModel() throws IOException {
    IndexWriterConfig cfg = new IndexWriterConfig(matchVersion, analyzer);
    IndexWriter writer = new IndexWriter(indexDir, cfg);
    writer.getConfig().setRAMBufferSizeMB(48);
    
    /* iterate SKOS concepts, create Lucene docs and add them to the index */
    ResIterator concept_iter = skosModel.listResourcesWithProperty(RDF.type,
        SKOS.Concept);
    while (concept_iter.hasNext()) {
      Resource skos_concept = concept_iter.next();
      
      Document concept_doc = createDocumentsFromConcept(skos_concept);
      if (concept_doc != null) {
        writer.addDocument(concept_doc);
      }
    }
    
    writer.close();
  }
  
  /** Returns the values of a given field for a given concept */
  private String[] readConceptFieldValues(String conceptURI, String field)
      throws IOException {
    
    Query query = new TermQuery(new Term(FIELD_URI, conceptURI));
    
    TopDocs docs = searcher.search(query, 1);
    
    ScoreDoc[] results = docs.scoreDocs;
    
    if (results.length != 1) {
      System.out.println("Unknown concept " + conceptURI);
      return null;
    }
    
    Document conceptDoc = searcher.doc(results[0].doc);
    
    return conceptDoc.getValues(field);
  }
}
