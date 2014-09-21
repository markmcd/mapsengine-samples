package com.google.mapsengine.tutorials;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.mapsengine.MapsEngine;
import com.google.api.services.mapsengine.MapsEngineScopes;
import com.google.api.services.mapsengine.model.DisplayRule;
import com.google.api.services.mapsengine.model.Feature;
import com.google.api.services.mapsengine.model.FeaturesBatchDeleteRequest;
import com.google.api.services.mapsengine.model.FeaturesBatchInsertRequest;
import com.google.api.services.mapsengine.model.FeaturesBatchPatchRequest;
import com.google.api.services.mapsengine.model.Filter;
import com.google.api.services.mapsengine.model.IconStyle;
import com.google.api.services.mapsengine.model.Layer;
import com.google.api.services.mapsengine.model.PointStyle;
import com.google.api.services.mapsengine.model.ProcessResponse;
import com.google.api.services.mapsengine.model.PublishResponse;
import com.google.api.services.mapsengine.model.Table;
import com.google.api.services.mapsengine.model.VectorStyle;
import com.google.api.services.mapsengine.model.ZoomLevels;
import com.google.maps.clients.BackOffWhenRateLimitedRequestInitializer;
import com.google.maps.clients.HttpRequestInitializerPipeline;
import com.google.maps.clients.mapsengine.geojson.Point;
import com.google.mapsengine.samples.auth.Utils;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Demonstrate updating an existing vector table on Google Maps Engine, by adding,
 * changing and deleting features in the table. As well as adding a new style rule to an existing
 * layer.
 *
 * You'll need the same credentials set up as the {@link CsvUpload} tutorial. You'll also need
 * the ID of the layer you created when running that tutorial. You can find any IDs through the
 * Maps Engine UI at https://mapsengine.google.com/admin/
 */
public class UpdateData {

  private static final String APPLICATION_NAME = "Google/MapsEngineUpdateData-1.0";
  private static final Collection<String> SCOPES = Arrays.asList(MapsEngineScopes.MAPSENGINE);
  private static final String NOWHERE_COUNTRY_CODE = "NWH";

  private MapsEngine engine;

  private final HttpTransport httpTransport = new NetHttpTransport();
  private final JsonFactory jsonFactory = new GsonFactory();

  public static void main(String[] args) {
    if (args.length < 1) {
      System.err.println("Usage: java ...UpdateData layerId");
      System.err.println(" tableId is the layer ID that you created in the CsvUpload tutorial.");
      System.exit(1);
    }

    try {
      new UpdateData().run(args[0]);
    } catch (Exception ex) {
      System.err.println("An unexpected error occurred!");
      ex.printStackTrace(System.err);
      System.exit(1);
    }
  }

  private void run(String layerId) throws IOException {
    System.out.println("Authorizing.");
    Credential credential = Utils.authorizeService(httpTransport, jsonFactory, SCOPES);
    System.out.println("Authorization successful!");

    // Set up the required initializers to 1) authenticate the request and 2) back off if we
    // start hitting the server too quickly.
    HttpRequestInitializer requestInitializers = new HttpRequestInitializerPipeline(
        Arrays.asList(credential, new BackOffWhenRateLimitedRequestInitializer()));

    // The MapsEngine object will be used to perform the requests.
    engine = new MapsEngine.Builder(httpTransport, jsonFactory, requestInitializers)
        .setApplicationName(APPLICATION_NAME)
        .build();

    System.out.println("Looking up layer.");
    Layer layer = engine.layers().get(layerId).execute();
    System.out.println("Done.");

    System.out.println("Looking up table ID from layer ID");
    Table table = engine.tables().get(layer.getDatasources().get(0).getId()).execute();
    System.out.println("Done.");

    // This is not part of the tutorial, but by clearing out any data from previous executions we
    // can run this tutorial multiple times on the same table. If it doesn't exist,
    // then the batchDelete call will still return successfully.
    System.out.println("Clearing out existing features.");
    deleteFeature(table, NOWHERE_COUNTRY_CODE);
    System.out.println("Done.");

    System.out.println("Adding a new feature.");
    insertFeature(table);
    System.out.println("Done.");

    System.out.println("Updating a feature.");
    updateFeature(table);
    System.out.println("Done.");

    System.out.println("Updating layer style.");
    updateLayerStyle(layer);
    System.out.println("Done.");

    System.out.println("Queueing layer for processing.");
    processLayer(layer);
    System.out.println("Done.");

    System.out.println("Publishing layer.");
    publishLayer(layer);
    System.out.println("Done.");

    System.out.println("Deleting a feature.");
    // See the tutorial for discussion on why we are deleting China's data.
    deleteFeature(table, "CHN");
    System.out.println("Done.");
  }

  /** Deletes the specified country code from the table along with its data. */
  private void deleteFeature(Table table, String country) throws IOException {
    FeaturesBatchDeleteRequest request = new FeaturesBatchDeleteRequest()
        .setPrimaryKeys(Arrays.asList(country));

    engine.tables().features().batchDelete(table.getId(), request).execute();
  }

  /** Adds a new feature to a table. */
  private void insertFeature(Table table) throws IOException {
    // Build the dictionary of feature properties
    Map<String, Object> properties = new HashMap<>();
    properties.put("POP_GROWTH", 1.1976742729);
    properties.put("COUNTRY", NOWHERE_COUNTRY_CODE);

    // Build the geometry. Note that this is using the Maps Engine API Wrapper for simplicity
    Point point = new Point(-34.0, 153.0);

    // Build the feature by attaching properties
    Feature newFeature = point.asFeature(properties);

    FeaturesBatchInsertRequest insertRequest = new FeaturesBatchInsertRequest()
        .setFeatures(Arrays.asList(newFeature));

    engine.tables().features().batchInsert(table.getId(), insertRequest).execute();
  }

  /** Updates a feature. */
  private void updateFeature(Table table) throws IOException {
    // Set the properties to patch
    Map<String, Object> properties = new HashMap<>();
    properties.put("COUNTRY", NOWHERE_COUNTRY_CODE); // Required: the primary key value to update
    properties.put("POP_GROWTH", 0);

    // Build the feature. We're not changing geometry so we can omit it.
    Feature updateFeature = new Feature()
        .setProperties(properties);

    FeaturesBatchPatchRequest patchRequest = new FeaturesBatchPatchRequest()
        .setFeatures(Arrays.asList(updateFeature));

    engine.tables().features().batchPatch(table.getId(), patchRequest).execute();
  }

  /** Updates the style of the layer to include an icon for zero population growth. */
  private void updateLayerStyle(Layer layer) throws IOException {
    // Define the additional layer style, setting an icon for stagnant growth (POP_GROWTH == 0)
    DisplayRule stagnantGrowth = new DisplayRule()
        .setZoomLevels(new ZoomLevels().setMin(0).setMax(24))
        .setPointOptions(new PointStyle()
            .setIcon(new IconStyle().setName("gx_donut")))
        .setFilters(Arrays.asList(new Filter()
            .setColumn("POP_GROWTH")
            .setOperator("==")
            .setValue(0)));

    // Add the new style to the list of styles
    List<DisplayRule> displayRules = layer.getStyle().getDisplayRules();
    displayRules.add(stagnantGrowth);

    // Build a shell layer containing just the styles
    VectorStyle style = new VectorStyle()
        .setType("displayRule")
        .setDisplayRules(displayRules);
    Layer newLayer = new Layer().setStyle(style);

    // And patch!
    engine.layers().patch(layer.getId(), newLayer).execute();
  }

  /** Queues the layer for processing, triggering the style update. */
  private ProcessResponse processLayer(Layer layer) throws IOException {
    return engine.layers().process(layer.getId()).execute();
  }

  /** Publishes the layer, making it visible. */
  private PublishResponse publishLayer(Layer layer) throws IOException {
    return engine.layers().publish(layer.getId()).execute();
  }

}
