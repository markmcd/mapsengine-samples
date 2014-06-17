package com.google.mapsengine.samples.auth;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;

import java.awt.Desktop;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;

/**
 * Common OAuth code used across the Java examples.
 */
public class Utils {

  private static final String CLIENT_SECRETS_FILE = "res/client_secrets.json";
  private static final File CREDENTIAL_STORE = new File(System.getProperty("user.home"),
      ".credentials/mapsengine.json");
  /**
   * Credentials are stored against a user ID. This app does not manage multiple identities and
   * will always authorize against the same user, so we use a single, default, user ID.
   */
  private static final String DEFAULT_USER_ID = "default";

  /**
   * Authorise the current user and store the credentials. This requires an interactive session
   * with a human and access to a web browser (using the "Installed Application" OAuth flow).
   * For details on how to perform human-free authorization, check the "Server to server" OAuth
   * flow.
   * @param httpTransport The HTTP transport to use for network requests.
   * @param jsonFactory The JSON factory to use for serialization / de-serialization.
   * @param scopes The scopes for which this app should authorize.
   */
  public static Credential authorizeUser(HttpTransport httpTransport, JsonFactory jsonFactory,
      Collection<String> scopes) throws IOException {
    File secretsFile = new File(CLIENT_SECRETS_FILE);
    if (!secretsFile.exists()) {
      System.err.println("Client secrets file not found. Check out the JavaDoc for details on how"
          + " to set up your client secrets.");
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
          new GoogleAuthorizationCodeFlow.Builder(httpTransport, jsonFactory, secrets, scopes)
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
      AssertionError newEx = new AssertionError("File not found should already be handled.");
      newEx.initCause(e);
      throw newEx;
    } finally {
      localServer.stop();
    }
  }
}
