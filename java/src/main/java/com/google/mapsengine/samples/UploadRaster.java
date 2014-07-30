package com.google.mapsengine.samples;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.mapsengine.MapsEngine;
import com.google.api.services.mapsengine.MapsEngine.Rasters;
import com.google.api.services.mapsengine.MapsEngineScopes;
import com.google.api.services.mapsengine.model.Raster;
import com.google.mapsengine.samples.auth.Utils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

/**
 * Demonstrate uploading a raster image through the API.
 */
public class UploadRaster {
  private static final String APPLICATION_NAME = "Google/MapsEngineTableDelete-1.0";
  private static final String MY_DRAFT_ACL = "Map Editors";
  private static final String MY_ATTRIBUTION = "Default Attribution";
  private static final Collection<String> SCOPES = Arrays.asList(MapsEngineScopes.MAPSENGINE);
  private static final Logger LOG = Logger.getLogger(UploadRaster.class.getName());

  private MapsEngine engine;

  private final HttpTransport httpTransport = new NetHttpTransport();
  private final JsonFactory jsonFactory = new GsonFactory();

  public static void main(String[] args) {
    try {
      new UploadRaster().run(args);
    } catch (Exception ex) {
      System.err.println("An unknown exception occurred!");
      ex.printStackTrace(System.err);
      System.exit(1);
    }
  }

  private void run(String[] args) throws IOException {
    if (args.length < 2) {
      System.err.println("Usage: java ...UploadRaster projectid filename1.ext [file2.ex2 ...]");
      System.err.println(" Where each file provided will be uploaded to a single image.");
      System.exit(1);
    }
    String projectId = args[0];
    String[] fileNames = Arrays.copyOfRange(args, 1, args.length);

    Credential credential = Utils.authorizeUser(httpTransport, jsonFactory, SCOPES);

    engine = new MapsEngine.Builder(httpTransport, jsonFactory, credential)
        .setApplicationName(APPLICATION_NAME)
        .build();

    Raster rasterTemplate = prepareImageUpload(projectId, fileNames);

    // Upload the files.
    for (String fileName : fileNames) {
      System.out.println("Processing " + fileName);
      uploadFile(rasterTemplate, fileName);
    }

    System.out.println("Done!");
  }

  /** Upload an empty asset to the API, containing the metadata. */
  private Raster prepareImageUpload(String projectId, String[] fileNames) throws IOException {
    // Build the list of files. Note that the File used here is *not* java.io.File.
    List<com.google.api.services.mapsengine.model.File> pendingFiles =
        new ArrayList<com.google.api.services.mapsengine.model.File>(fileNames.length);
    for (String filename : fileNames) {
      pendingFiles.add(new com.google.api.services.mapsengine.model.File().setFilename(filename));
    }

    // Create an empty raster.
    Raster emptyRaster = new Raster()
        .setProjectId(projectId)
        .setName(fileNames[0])  // use the first filename as the target upload name
        .setFiles(pendingFiles)
        .setDraftAccessList(MY_DRAFT_ACL)
        .setAttribution(MY_ATTRIBUTION) // This references the attribution name, as set up in the UI
        .setRasterType("image");

    return engine.rasters().upload(emptyRaster).execute();
  }

  /** Upload a file to the empty image, with a progress indicator. */
  private void uploadFile(Raster emptyImage, String fileName) throws IOException {
    // This is a java.io.File.
    File file = new File(fileName);
    InputStream fileInputStream = new BufferedInputStream(new FileInputStream(file));
    // Files.probeContentType requires Java 7.
    String contentType = Files.probeContentType(file.toPath());
    InputStreamContent contentStream = new InputStreamContent(contentType, fileInputStream);
    contentStream.setLength(file.length()); // optional, but required for tracking progress below.

    Rasters.Files.Insert uploadRequest =
        engine.rasters().files().insert(emptyImage.getId(), fileName, contentStream);

    // This part is optional, but allows us to monitor the progress.
    uploadRequest.getMediaHttpUploader().setProgressListener(new MediaHttpUploaderProgressListener() {
      @Override
      public void progressChanged(MediaHttpUploader uploader) throws IOException {
        switch (uploader.getUploadState()) {
          case INITIATION_STARTED:
            LOG.info("Initiation has started!");
            break;
          case INITIATION_COMPLETE:
            LOG.info("Initiation is complete!");
            break;
          case MEDIA_IN_PROGRESS:
            LOG.info(String.format("%.2f%%", uploader.getProgress() * 100.0));
            break;
          case MEDIA_COMPLETE:
            LOG.info("Upload is complete!");
            break;
        }
      }
    });

    // Do it!
    uploadRequest.execute();
  }
}
