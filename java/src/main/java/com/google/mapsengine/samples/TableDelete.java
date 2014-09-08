package com.google.mapsengine.samples;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.mapsengine.MapsEngine;
import com.google.api.services.mapsengine.MapsEngineScopes;
import com.google.api.services.mapsengine.model.Asset;
import com.google.api.services.mapsengine.model.Map;
import com.google.api.services.mapsengine.model.MapFolder;
import com.google.api.services.mapsengine.model.MapItem;
import com.google.api.services.mapsengine.model.MapLayer;
import com.google.api.services.mapsengine.model.Parent;
import com.google.api.services.mapsengine.model.ParentsListResponse;
import com.google.maps.clients.BackOffWhenRateLimitedRequestInitializer;
import com.google.maps.clients.HttpRequestInitializerPipeline;
import com.google.mapsengine.samples.auth.Utils;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Demonstrate the DELETE actions and the parents endpoints.
 *
 * Specify the asset ID of a table on the command-line to delete it forcibly,
 * removing any layers and maps that depend on it and clearing out assets that would be left
 * unused after deletion. The app will NOT prompt for confirmation before deleting.
 */
public class TableDelete {
  private static final String APPLICATION_NAME = "Google/MapsEngineTableDelete-1.0";
  private static final Collection<String> SCOPES = Arrays.asList(MapsEngineScopes.MAPSENGINE);
  private static final Logger LOG = Logger.getLogger(TableDelete.class.getName());

  private MapsEngine engine;

  private final HttpTransport httpTransport = new NetHttpTransport();
  private final JsonFactory jsonFactory = new GsonFactory();

  public static void main(String[] args) {
    try {
      new TableDelete().run(args);
    } catch (Exception ex) {
      LOG.log(Level.SEVERE, "An unexpected error occurred!", ex);
      System.exit(1);
    }
  }

  private void run(String[] args) throws IOException {
    String tableId = parseArgs(args);

    // Log in
    Credential credential = Utils.authorizeUser(httpTransport, jsonFactory, SCOPES);

    // Set up automatic retry of failed requests.
    HttpRequestInitializerPipeline initializers = new HttpRequestInitializerPipeline(credential,
        new BackOffWhenRateLimitedRequestInitializer());

    engine = new MapsEngine.Builder(httpTransport, jsonFactory, initializers)
        .setApplicationName(APPLICATION_NAME)
        .build();

    if (!validateId(tableId)) {
      throw new AssertionError("Provided ID is not a table, missing, "
          + "invalid or you don't have access.");
    }

    LOG.info("Attempting to delete table ID: " + tableId);
    deleteTable(tableId);
  }

  private String parseArgs(String[] args) {
    String tableId = "";
    if (args.length != 1) {
      System.err.println("Usage: java ... TableDelete tableId");
      System.err.println(" tableId is the ID string of the table to delete.");
      System.err.println(" WARNING: This will delete without further confirmation!");
      System.err.println(" WARNING: This will delete the table specified as well as any "
          + "layers or maps that use it!");
      System.err.println(" This will not delete maps using other layers.");
      System.exit(1);
    } else {
      tableId = args[0];
    }
    return tableId;
  }

  /** Ensures the given ID belongs to a table and that the user can access it. */
  private boolean validateId(String tableId) throws IOException {
    try {
      Asset asset = engine.assets().get(tableId).execute();
      return "table".equalsIgnoreCase(asset.getType());
    } catch (GoogleJsonResponseException ex) {
      // A "400 Bad Request" is thrown when the asset ID is missing or invalid
      return false;
    }
  }

  /** Deletes a table, including any layers displaying the table. */
  private void deleteTable(String tableId) throws IOException {
    LOG.info("Finding layers belonging to table.");
    ParentsListResponse tableParents = engine.tables().parents().list(tableId).execute();
    LOG.info("Layers retrieved.");

    // Collect the layer IDs to ensure we can safely delete maps.
    Set<String> allLayerIds = new HashSet<String>();
    for (Parent tableParent : tableParents.getParents()) {
      allLayerIds.add(tableParent.getId());
    }

    // We need to delete layers before we can delete the table.
    deleteLayers(allLayerIds);

    LOG.info("Deleting table.");
    engine.tables().delete(tableId).execute();
    LOG.info("Table deleted.");

  }

  /** Deletes the provided layers, including any maps where they are used. */
  private void deleteLayers(Set<String> layerIds) throws IOException {
    for (String layerId : layerIds) {
      assertLayerIsNotPublished(layerId);

      LOG.info("Layer ID: " + layerId + ", finding maps.");
      ParentsListResponse layerParents = engine.layers().parents().list(layerId).execute();
      // Delete each layer. Note that these operations are not transactional,
      // so if a later operation fails, the earlier assets will still be deleted.
      for (Parent layerParent : layerParents.getParents()) {
        String mapId = layerParent.getId();
        deleteMap(layerIds, mapId);
      }

      LOG.info("Deleting layer.");
      engine.layers().delete(layerId).execute();
      LOG.info("Layer deleted.");
    }
  }

  // TODO(macd): Update this to edit the map, once available in the API.
  /** Safely deletes a map, as long as all layers contained are scheduled for deletion. */
  private void deleteMap(Set<String> layerIdsPendingDeletion, String mapId) throws IOException {
    assertMapIsNotPublished(mapId);

    LOG.info("Checking for other layers on this map (ID: " + mapId + ")");
    Set<String> mapLayerIds = getLayerIdsFromMap(mapId);

    // Determine if this map will still have layers once we perform our delete.
    mapLayerIds.removeAll(layerIdsPendingDeletion);
    if (mapLayerIds.size() == 0) {
      // Map will not contain any more Layers when done, so delete it.
      LOG.info("Deleting map.");
      engine.maps().delete(mapId).execute();
      LOG.info("Map deleted.");
    } else {
      // Map will contain Layers not scheduled for deletion, so we can't continue.
      throw new IllegalStateException("Map " + mapId + " contains layers not scheduled for "
          + "deletion. You will need to remove them before we can delete this map.");
    }
  }

  /** Ensures that a layer is not published. Useful to test before deleting. */
  private void assertLayerIsNotPublished(String layerId) throws IOException {
    boolean publishedVersionExists;
    try {
      engine.layers().get(layerId).setVersion("published").execute();
      publishedVersionExists = true;
    } catch (GoogleJsonResponseException ex) {
      // The API failed to retrieve a published version.
      publishedVersionExists = false;
    }

    if (publishedVersionExists) {
      throw new AssertionError("Layer ID " + layerId + " is published, "
          + "please un-publish before deleting.");
    }
  }

  /** Ensures that a map is not published. Useful to test before deleting. */
  private void assertMapIsNotPublished(String mapId) throws IOException {
    Map map = engine.maps().get(mapId).execute();
    if (map.getVersions().contains("published")) {
      throw new AssertionError("Map ID " + mapId + " is published, "
          + "please un-publish before deleting.");
    }
  }

  /** Finds all layers attached to a map. */
  private Set<String> getLayerIdsFromMap(String mapId) throws IOException {
    // Retrieve the map.
    Map map = engine.maps().get(mapId).execute();

    // Find the layers
    Set<String> layerIds = new HashSet<String>();
    List<MapItem> mapContents = map.getContents();
    while (mapContents != null && mapContents.size() > 0) {
      MapItem item = mapContents.remove(0);
      if (item instanceof MapLayer) {
        layerIds.add(((MapLayer) item).getId());
      } else if (item instanceof MapFolder) {
        mapContents.addAll(((MapFolder) item).getContents());
      }
      // MapKmlLinks do not have IDs
    }

    return layerIds;
  }
}
