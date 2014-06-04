package com.google.mapsengine.samples;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
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

import au.com.bytecode.opencsv.CSVReader;

import java.awt.Desktop;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
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
 *  - There must be a 'lat' column and a 'lng' column, which are used to generate a Point
 *  - Column types are inferred by the first row of data (2nd row in the file)
 *    - If the value can be parsed as an integer, the column becomes an integer type
 *    - Otherwise the column is a string
 */
public class CsvUpload {

  private static final String APPLICATION_NAME = "Google/MapsEngineCsvUpload-1.0";
  private static final String CLIENT_SECRETS_FILE = "res/client_secrets.json";
  private static final File CREDENTIAL_STORE = new File(System.getProperty("user.home"),
      ".credentials/mapsengine.json");
  private static final Collection<String> SCOPES = Arrays.asList(MapsEngineScopes.MAPSENGINE);
  private static final String DEFAULT_ACCESS_LIST = "Map Editors";

  /**
   * Credentials are stored against a user ID. This app does not manage multiple identities and
   * will always authorize against the same user, so we use a single, default,  user ID.
   */
  private static final String DEFAULT_USER_ID = "default";

  private static final String LAT_COLUMN_NAME = "lat";
  private static final String LNG_COLUMN_NAME = "lng";
  private static final int NOT_SEEN = -1;

  private final List<Feature> tableData = new ArrayList<Feature>();
  private Schema schema;
  private MapsEngine engine;

  private final HttpTransport httpTransport = new NetHttpTransport();
  private final JsonFactory jsonFactory = new GsonFactory();

  public static void main(String[] args) {
    try {
      new CsvUpload().run(args);
    } catch (Exception ex) {
      System.err.println("An unexpected error occurred!");
      ex.printStackTrace(System.err);
      System.exit(1);
    }
  }

  public void run(String[] args) throws IOException {

    if (args.length < 2) {
      System.err.println("Usage: java ... CsvUpload myfile.csv projectId");
      System.err.println(" myfile.csv is the path to the CSV file to upload");
      System.err.println(" projectId is the numerical ID of the project in which to create the "
          + "new table");
      System.exit(1);
    }
    String fileName = args[0];
    String projectId = args[1];

    System.out.println("Loading CSV data from " + fileName);
    loadCsvData(fileName);

    System.out.println("Authorizing. If this takes a while, check your browser.");
    Credential credential = authorizeUser();
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

    System.out.print("Waiting for layer processing to complete");
    layer = waitUntilLayerProcessed(layer);
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
      throw new AssertionError("File not found should already be handled", e);
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

  /**
   * Authorise the current user and store the credentials. This requires an interactive session
   * with a human and access to a web browser (using the "Installed Application" OAuth flow).
   * For details on how to perform human-free authorization, check the "Server to server" OAuth
   * flow.
   */
  private Credential authorizeUser() throws IOException {
    File secretsFile = new File(CLIENT_SECRETS_FILE);
    if (!secretsFile.exists()) {
      System.err.println("Client secrets file not found. Check out the JavaDoc for CsvUpload for "
          + "details on how to set up your client secrets.");
      System.exit(1);
    }

    // Set up a local server to capture the authorization response from Google.
    LocalServerReceiver localServer = new LocalServerReceiver();

    try {
      // Load the client secret details from file.
      GoogleClientSecrets secrets = GoogleClientSecrets.load(jsonFactory,
          new FileReader(secretsFile));

      // This credential store will persist tokens between application executions,
      // so you don't need to keep authorizing.
      FileDataStoreFactory credentialStore = new FileDataStoreFactory(CREDENTIAL_STORE);

      GoogleAuthorizationCodeFlow flow =
          new GoogleAuthorizationCodeFlow.Builder(httpTransport, jsonFactory, secrets, SCOPES)
            .setDataStoreFactory(credentialStore)
            .build();

      // If we've run before, then we can just used the stored credentials. The empty string
      Credential credential = flow.loadCredential(DEFAULT_USER_ID);
      if (credential != null) {
        return credential;
      }

      // Open the default web browser to confirm the user's authorization
      if (!Desktop.isDesktopSupported()) {
        throw new IllegalStateException("Unable to launch web browser. Desktop support is "
            + "required for this application.");
      }
      // Set our local server URL as the point to return the user to,
      // so we know when we're complete.
      String localRedirectUri = localServer.getRedirectUri();
      URI authUri = flow.newAuthorizationUrl().setRedirectUri(localRedirectUri).toURI();
      Desktop.getDesktop().browse(authUri);

      // Wait for the authorization code to come back.
      String code = localServer.waitForCode();
      // Turn the auth code into a token.
      GoogleTokenResponse tokenResponse = flow.newTokenRequest(code)
          .setRedirectUri(localRedirectUri).execute();

      // You may want to use a non-empty user ID here if your app has more than one user.
      return flow.createAndStoreCredential(tokenResponse, DEFAULT_USER_ID);

    } catch (FileNotFoundException e) {
      throw new AssertionError("File not found should already be handled.", e);
    } finally {
      localServer.stop();
    }
  }

  /** Creates an empty table in your maps engine account. */
  private Table createTable(String tableName, Schema schema, String projectId) throws IOException {
    Table newTable = new Table()
        .setName(tableName)
        .setProjectId(projectId)
        .setDraftAccessList(DEFAULT_ACCESS_LIST)
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
        .setDatasourceType("table")
        .setDraftAccessList(DEFAULT_ACCESS_LIST)
        .setName(table.getName())
        .setProjectId(table.getProjectId())
        .setDatasources(Arrays.asList(new Datasource().setId(table.getId())))
        .setStyle(style);

    return engine.layers().create(newLayer)
        .setProcess(true) // flag that this layer should be processed immediately
        .execute();
  }

  /** Block until the provided layer has been marked as processed. Returns the new layer. */
  private Layer waitUntilLayerProcessed(Layer layer) {
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
        .setName(layer.getName())
        .setDraftAccessList(DEFAULT_ACCESS_LIST);

    List<MapItem> layers = new ArrayList<MapItem>();

    MapLayer layer1 = new MapLayer()
        .setId(layer.getId())
        .setVisibility("defaultOn")
        .setKey("layer"); // 'layer' is the key for referring to this layer in shorthand
    layers.add(layer1);

    newMap.setContents(layers);

    return engine.maps().create(newMap).execute();
  }

  /** Mark the provided map as "published", making it visible. */
  private PublishResponse publishMap(Map map) throws IOException {
    while (true) {
      try {
        System.out.print(".");
        return engine.maps().publish(map.getId()).execute();
      } catch (GoogleJsonResponseException ex) {
        // Unfortunately we have no way to test that publishing has succeeded other than catching
        // this exception.
      }
    }
  }

}
