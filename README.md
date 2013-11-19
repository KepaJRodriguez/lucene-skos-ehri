# SKOS Support for Apache Lucene and Solr for EHRI portal 

EHRI extension of lucene-skos Solr/Lucene plugin

The address of the modified plugin is:
	https://github.com/behas/lucene-skos/tree/0.4-dev

Installation
============

Clone the sources
	git clone https://github.com/KepaJRodriguez/lucene-skos-ehri

Build and package
	cd lucene-skos-ehri
	mvn package

Create a "distr/out" directory in "distr"
	mkdir distr/out

Unpack *tar.gz file with the libraries in distr/out 
	tar zxvf distr/lucene-skos-ehri-0.4.5.tar.gz -C distr/out


Copy the libraries into the solr/lib directory
	cp distr/out/*jar $SOLR_HOME/example/solr/lib

Copy the controlled vocabularies into the solr/portal/conf directory
	cp ehri-skos.rdf $SOLR_HOME/example/solr/portal/conf
	and the same for other multilingual authority files


Index time expansion
====================

We add the filters in the text_general field indexer as follow:

 	 <fieldType name="text_general" class="solr.TextField" positionIncrementGap="100">
	    <analyzer type="index">
		.....
	   <filter class="at.ac.univie.mminf.luceneSKOS.solr.SKOSFilterFactory"
        skosFile="ehri-skos-experimental.rdf" expansionType="LABEL" type="PREF ALT BROADER PREFMALE PREFFEMALE" bufferSize="50" />
 	    <filter class="at.ac.univie.mminf.luceneSKOS.solr.SKOSFilterFactory"
 	       skosFile="ghettos-multiling-skos.rdf" expansionType="LABEL" type="PREF ALT BROADER" bufferSize="50" /> 
 	    <filter class="at.ac.univie.mminf.luceneSKOS.solr.SKOSFilterFactory"
 	       skosFile="camps-multiling-skos.rdf" expansionType="LABEL" type="PREF ALT BROADER" bufferSize="50" />
		.....
 	   </analyzer>

After we start Solr the first time we have to wait until each vocabulary is indexed. We do it in several steps:
	1 Add the first filter (or comment out the second and third filters)
	2 Start Solr
	3 Shutdown Solr
	4 Add the second filter
	5 Start Solr
	6 Shutdown Sor
	.... etc

A new directory is created: $Solr_home/example/skosdata with the indexes of the vocabularies



