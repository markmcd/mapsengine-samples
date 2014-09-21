package com.google.mapsengine.samples;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.json.GoogleJsonError.ErrorInfo;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.mapsengine.MapsEngine;
import com.google.api.services.mapsengine.MapsEngineScopes;
import com.google.api.services.mapsengine.model.Datasource;
import com.google.api.services.mapsengine.model.DisplayRule;
import com.google.api.services.mapsengine.model.Feature;
import com.google.api.services.mapsengine.model.FeaturesBatchInsertRequest;
import com.google.api.services.mapsengine.model.IconStyle;
import com.google.api.services.mapsengine.model.Layer;
import com.google.api.services.mapsengine.model.Map;
import com.google.api.services.mapsengine.model.MapItem;
import com.google.api.services.mapsengine.model.MapLayer;
import com.google.api.services.mapsengine.model.PointStyle;
import com.google.api.services.mapsengine.model.PublishResponse;
import com.google.api.services.mapsengine.model.Schema;
import com.google.api.services.mapsengine.model.Table;
import com.google.api.services.mapsengine.model.TableColumn;
import com.google.api.services.mapsengine.model.VectorStyle;
import com.google.api.services.mapsengine.model.ZoomLevels;
import com.google.maps.clients.BackOffWhenRateLimitedRequestInitializer;
import com.google.maps.clients.HttpRequestInitializerPipeline;
import com.google.maps.clients.mapsengine.geojson.Point;
import com.google.mapsengine.samples.auth.Utils;

import au.com.bytecode.opencsv.CSVReader;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

/**
 * Demonstrate uploading local CSV file into a new Maps Engine table,
 * creating a layer and map in the process.
 *
 * To get started, copy the res/client_secrets.json.example file to res/client_secrets.json and
 * update the file with your client ID and secret.  Alternatively, the credentials screen of
 * https://console.developers.google.com/ will have a "Download JSON" button you can use to save
 * to this location. You can set up your authorization screen here, too.
 *
 * The table schema is inferred from the file in a naive fashion, according to these rules:
 *  - Column names are taken from the first row in the file.
 *  - The first column is used as the ID column
 *  - There must be a "lat" column and a "lng" column, which are used to generate a Point
 *  - Column types are inferred by the first row of data (2nd row in the file)
 *    - If the value can be parsed as an integer, the column becomes an integer type
 *    - Otherwise the column is a string
 */
public class CsvBatchInsert {

  private static final String APPLICATION_NAME = "Google/MapsEngineBatchInsert-1.0";
  private static final Collection<String> SCOPES = Arrays.asList(MapsEngineScopes.MAPSENGINE);

  private static final String LAT_COLUMN_NAME = "lat";
  private static final String LNG_COLUMN_NAME = "lng";
  private static final int NOT_SEEN = -1;

  private final List<Feature> tableData = new ArrayList<Feature>();
  private Schema schema;
  private MapsEngine engine;

  private final HttpTransport httpTransport = new NetHttpTransport();
  private final JsonFactory jsonFactory = new GsonFactory();

  public static void main(String[] args) {
    if (args.length < 2) {
      System.err.println("Usage: java ... CsvBatchInsert myfile.csv projectId");
      System.err.println(" myfile.csv is the path to the CSV file to upload");
      System.err.println(" projectId is the numerical ID of the project in which to create the "
          + "new table");
      System.exit(1);
    }

    try {
      new CsvBatchInsert().run(args[0], args[1]);
    } catch (Exception ex) {
      System.err.println("An unexpected error occurred!");
      ex.printStackTrace(System.err);
      System.exit(1);
    }
  }

  public void run(String fileName, String projectId) throws IOException {
    System.out.println("Loading CSV data from " + fileName);
    loadCsvData(fileName);

    System.out.println("Authorizing. If this takes a while, check your browser.");
    Credential credential = Utils.authorizeUser(httpTransport, jsonFactory, SCOPES);
    System.out.println("Authorization successful!");

    // Set up the required initializers to 1) authenticate the request and 2) back off if we
    // start hitting the server too quickly.
    HttpRequestInitializer requestInitializers = new HttpRequestInitializerPipeline(
        Arrays.asList(credential, new BackOffWhenRateLimitedRequestInitializer()));

    // The MapsEngine object will be used to perform the requests.
    engine = new MapsEngine.Builder(httpTransport, jsonFactory, requestInitializers)
        .setApplicationName(APPLICATION_NAME)
        .build();

    System.out.println("Creating an empty table in Maps Engine, under project ID " + projectId);
    Table table = createTable(fileName, schema, projectId);
    System.out.println("Table created, ID is: " + table.getId());

    System.out.println("Starting the batch insert operation.");
    insertData(table, tableData);
    System.out.println("Done. Inserted " + tableData.size() + " rows.");

    System.out.println("Creating a new layer.");
    Layer layer = createLayer(table);
    System.out.println("Layer created, ID is: " + layer.getId());

    System.out.print("Processing layer.");
    layer = processLayer(layer);
    System.out.println(" done!");

    System.out.println("Publishing layer.");
    publishLayer(layer);
    System.out.println("Done.");

    System.out.println("Creating a new map.");
    Map map = createMap(layer);
    System.out.println("Map created, ID is: " + map.getId());

    System.out.print("Publishing map.");
    publishMap(map);
    System.out.println(" done.");
    System.out.println("Publishing complete. You can view the map here: "
        + String.format("https://mapsengine.google.com/%s/mapview/?authuser=0", map.getId()));

  }

  /** Defines a mapping between a Maps Engine table schema and our equivalent CSV model. */
  private static class CsvSchema {
    Schema tableSchema;
    java.util.Map<Integer, String> columnIndexToName;
    int latIndex;
    int lngIndex;
  }

  /** Open the file described and load its data. */
  private void loadCsvData(String fileName) throws IOException {
    File inputFile = new File(fileName);
    if (!inputFile.exists()) {
      System.err.println("File " + fileName + " does not exist!");
      System.exit(1);
    }

    try {
      CSVReader reader = new CSVReader(new FileReader(inputFile));
      String[] columns = reader.readNext();
      String[] line = reader.readNext();
      CsvSchema csvSchema = generateSchema(columns, line);
      schema = csvSchema.tableSchema;

      while (line != null) {
        java.util.Map<String, Object> properties = new HashMap<String, Object>(line.length);
        for (int i = 0; i < line.length; i++) {
          if (i != csvSchema.latIndex && i != csvSchema.lngIndex) {
            // Put: [ column name, row value ]
            properties.put(csvSchema.columnIndexToName.get(i), line[i]);
          }
        }
        // Create the Geometry for this row.
        Point geometry = new Point(Double.parseDouble(line[csvSchema.latIndex]),
            Double.parseDouble(line[csvSchema.lngIndex]));

        // Convert the Geometry into a Feature by adding properties.  Then save it.
        tableData.add(geometry.asFeature(properties));

        line = reader.readNext();
      }

    } catch (FileNotFoundException e) {
      // This should be guarded by the File.exists() checks above.
      AssertionError newEx = new AssertionError("File not found should already be handled");
      newEx.initCause(e);
      throw newEx;
    }
  }

  /**
   * Generate the table schema from the header and first data row of the CSV input.
   * @param csvHeaderLine  The fields representing the header row of the CSV file.
   * @param firstRow  The fields representing the first data row of the CSV file.
   */
  private static CsvSchema generateSchema(String[] csvHeaderLine, String[] firstRow) {
    if (csvHeaderLine.length < 3) {
      throw new IllegalArgumentException("CSV header requires at least 3 fields: an ID column,"
          + " a lat column and a lng column.");
    }

    CsvSchema csvSchema = new CsvSchema();
    csvSchema.columnIndexToName = new HashMap<Integer, String>(csvHeaderLine.length);
    List<TableColumn> columns = new ArrayList<TableColumn>();

    // The geometry column must be first.  We only handle points in this sample.
    columns.add(new TableColumn().setName("geometry").setType("points"));

    // Iterate over the columns.
    for (int i = 0; i < csvHeaderLine.length; i++) {
      String columnName = csvHeaderLine[i];

      // Ensure that we have seen the lat and lng columns, omitting them from the schema as they
      // map to the geometry column.
      if (LAT_COLUMN_NAME.equals(columnName)) {
        csvSchema.latIndex = i;
      } else if (LNG_COLUMN_NAME.equals(columnName)) {
        csvSchema.lngIndex = i;
      } else {
        // Infer the column type: if it looks like an integer, make it so. Default to string.
        boolean isInteger = false;
        try {
          Integer.parseInt(firstRow[i]);
          isInteger = true;
        } catch (NumberFormatException ex) {
          // Do nothing, isInteger should already be false.
        }

        TableColumn col = new TableColumn();
        col.setName(columnName);

        // The first (ID) column must be a string, even if it's numeric.
        if (isInteger && i != 0) {
          col.setType("integer");
        } else {
          col.setType("string");
        }

        columns.add(col);
        csvSchema.columnIndexToName.put(i, columnName);
      }
    }

    // Ensure that both a lat and a lng column have been provided.
    if (csvSchema.latIndex == NOT_SEEN || csvSchema.lngIndex == NOT_SEEN) {
      throw new IllegalArgumentException("Input CSV does not contain both 'lat' and 'lng' columns");
    }

    Schema schema = new Schema();
    schema.setColumns(columns);
    // Set the first column in the file as the ID column.
    schema.setPrimaryKey(csvHeaderLine[0]);
    csvSchema.tableSchema = schema;
    return csvSchema;
  }

  /** Creates an empty table in your maps engine account. */
  private Table createTable(String tableName, Schema schema, String projectId) throws IOException {
    Table newTable = new Table()
        .setName(tableName)
        .setProjectId(projectId)
        .setSchema(schema)
        .setTags(Arrays.asList("CSV Upload", "Samples"));
    return engine.tables().create(newTable).execute();
  }

  /** Performs a batch insert of data into the table. */
  private void insertData(Table table, List<Feature> features) throws IOException {
    FeaturesBatchInsertRequest payload = new FeaturesBatchInsertRequest()
        .setFeatures(features);
    engine.tables().features().batchInsert(table.getId(), payload).execute();
  }

  /** Creates a layer using the table provided. */
  private Layer createLayer(Table table) throws IOException {
    // Create a basic layer style. For more detail on the different icons available to use,
    // check out the list: https://www.google.com/fusiontables/DataSource?dsrcid=308519#map:id=3
    // Prefix any listed name with 'gx_' to use in your own app.
    VectorStyle style = new VectorStyle()
        .setType("displayRule")
        .setDisplayRules(Arrays.asList(
            new DisplayRule()
                .setZoomLevels(new ZoomLevels().setMax(24).setMin(0))
                .setPointOptions(new PointStyle()
                    .setIcon(new IconStyle().setName("gx_go"))) // 'go' marker icon.
        ));

    Layer newLayer = new Layer()
        .setLayerType("vector")
        .setName(table.getName())
        .setProjectId(table.getProjectId())
        .setDatasources(Arrays.asList(new Datasource().setId(table.getId())))
        .setStyle(style);

    return engine.layers().create(newLayer)
        .setProcess(false) // flag that this layer should not be processed immediately
        .execute();
  }

  /** Block until the provided layer has been marked as processed. Returns the new layer. */
  private Layer processLayer(Layer layer) throws IOException {
    // Initiate layer processing.
    try {
      engine.layers().process(layer.getId()).execute();
    } catch (GoogleJsonResponseException ex) {
      // We only continue if there is exactly one error, as >1 error indicates an additional,
      // unknown problem that we are unable to handle. Zero errors is also unexpected.
      if (ex.getDetails().getErrors().size() == 1) {
        ErrorInfo error = ex.getDetails().getErrors().get(0);
        // If we "fail" because the layer is already processed, then it's safe to continue. In
        // any other case we want to re-throw the error.
        if (!"processingUpToDate".equals(error.getReason())) {
          throw ex;
        }
      } else {
        throw ex;
      }
    }

    while (!"complete".equals(layer.getProcessingStatus())) {
      // This is safe to run in a while loop as it executes synchronously and we have used a
      // BackOffWhenRateLimitedRequestInitializer when creating the engine.
      try {
        layer = engine.layers().get(layer.getId()).execute();
        System.out.print(".");
      } catch (IOException ex) {
        // If we lose network connectivity here, it's safe to blindly retry.
        System.out.print("?");
      }
    }
    return layer;
  }

  /** Publish the given Layer */
  private PublishResponse publishLayer(Layer layer) throws IOException {
    return engine.layers().publish(layer.getId()).execute();
  }

  /** Creates a map using the layer provided */
  private Map createMap(Layer layer) throws IOException {
    Map newMap = new Map()
        .setProjectId(layer.getProjectId())
        .setName(layer.getName());

    List<MapItem> layers = new ArrayList<MapItem>();

    MapLayer layer1 = new MapLayer()
        .setId(layer.getId())
        .setVisibility("defaultOn")
        .setKey("layer"); // 'layer' is the key for referring to this layer in shorthand
    layers.add(layer1);

    newMap.setContents(layers);

    // Map processing is triggered automatically, so no need to set a flag during creation.
    return engine.maps().create(newMap).execute();
  }

  /** Mark the provided map as "published", making it visible. */
  private PublishResponse publishMap(Map map) throws IOException {
    while (true) {
      try {
        System.out.print(".");
        // Initially the map will be in a 'processing' state and will return '400 Bad Request'
        // while processing is happening. Keep trying until it works.
        return engine.maps().publish(map.getId()).execute();
      } catch (GoogleJsonResponseException ex) {
        // Silently ignore the expected error.
      }
    }
  }

}
