Maps Engine Java Samples
========================

This repository hosts a number of samples and tutorials written in Java for Google Maps Engine.  These
samples are included directly into the [Maps Engine documentation](https://developers.google.com/maps-engine/).

[![Build Status](https://api.travis-ci.org/googlemaps/mapsengine-samples.svg)](https://travis-ci.org/googlemaps/mapsengine-samples)
![Analytics](https://ga-beacon.appspot.com/UA-12846745-20/mapsengine-samples/java/readme?pixel)

Many of these samples will use the Maps Engine Java API wrapper, which you can check out [here](https://github.com/googlemaps/mapsengine-api-java-wrapper).

Tutorials
=========

The tutorial samples live in the [tutorials directory](src/main/java/com/google/mapsengine/tutorials). Each file corresponds to a single tutorial in the Maps Engine docs.

* Create Map
 * [Tutorial](https://developers.google.com/maps-engine/documentation/tutorial-create-map)
 * [Code](src/main/java/com/google/mapsengine/tutorials/CsvUpload.java)
* Update Data
 * [Tutorial](https://developers.google.com/maps-engine/documentation/tutorial-update-data)
 * [Code](src/main/java/com/google/mapsengine/tutorials/UpdateData.java)

To run these, follow the preparation instructions in the JavaDoc at the start of each file, build a fat JAR and run the class from the command line.

    ./gradlew clean fatJar
    java -cp build/libs/mapsengine-samples-java-all-*.jar com.google.mapsengine.tutorials.ClassName

Be sure to substitute the appropriate class name for `ClassName`.


Web Server OAuth Sample
=======================

This is a very basic sample demonstrating the Web Server OAuth flow.  Run it like so.

    gradle execute -PmainClass=com.google.mapsengine.samples.auth.WebServer

CSV Upload Sample
=================

Upload a CSV file into Maps Engine, inferring schema and creating a published map. This demonstrates the full
stack of create and publish requests required to get to a published map. Specifically, these tasks:

 * Authorize a user (using the "Installed Application" OAuth flow)
 * Create a table
 * Upload data into table
 * Create a layer
 * Publish layer
 * Create a map
 * Publish map

Run it like so.

    gradle build
    java -cp build/libs/mapsengine-samples-java-all-*.jar com.google.mapsengine.samples.CsvUpload path/to/file.csv projectId
    
A sample CSV file is provided in res/simpletable.csv and you will have to create your own project through the
[web interface](https://mapsengine.google.com/admin/).
