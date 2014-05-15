package com.google.mapsengine.samples.auth;

import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;

import org.mortbay.jetty.Server;
import org.mortbay.jetty.servlet.ServletHandler;
import org.mortbay.jetty.servlet.SessionHandler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Scanner;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Sample demonstrating the web server OAuth flow with Maps Engine.  The main method starts a web
 * server on port 5678 by default, run this and head to http://localhost:5678/ to see it working.
 */
class WebServer {
  /** Port upon which to host our local server. */
  public static final int SERVER_PORT = 5678;

  /** Default HTTP transport to use to make HTTP requests. */
  private static final HttpTransport TRANSPORT = new NetHttpTransport();

  /** Default JSON factory to use to deserialize JSON. */
  private static final JsonFactory JSON_FACTORY = new GsonFactory();

  /** Creates a client secrets object from the client_secrets.json file. */
  private static GoogleClientSecrets clientSecrets;

  static {
    try {
      Reader reader = new FileReader("client_secrets.json");
      clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, reader);
    } catch (IOException e) {
      throw new Error("No client_secrets.json found", e);
    }
  }

  /** This is the Client ID that you generated in the API Console. */
  private static final String CLIENT_ID = clientSecrets.getWeb().getClientId();

  /** This is the Client Secret that you generated in the API Console. */
  //private static final String CLIENT_SECRET = clientSecrets.getWeb().getClientSecret();

  /** Replace this with your application's name. */
  private static final String APPLICATION_NAME = "Google Maps Engine Java Quickstart";


  /**
   * Start the server and set up the URL mappings/
   */
  public static void main(String[] args) throws Exception {
    Server server = new Server(SERVER_PORT);
    ServletHandler servletHandler = new ServletHandler();
    SessionHandler sessionHandler = new SessionHandler();
    sessionHandler.setHandler(servletHandler);
    server.setHandler(sessionHandler);
    servletHandler.addServletWithMapping(MainServlet.class, "/");
    server.start();
    server.join();
  }

  /**
   * Main, "index" servlet for the top-level URL, "/".
   */
  public static class MainServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws
        ServletException, IOException {
      // This check prevents the "/" handler from handling all requests by default
      if (!"/".equals(request.getServletPath())) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return;
      }

      response.setContentType("text/html");
      try {
        // Create a state token to prevent request forgery.
        // Store it in the session for later validation.
        String state = new BigInteger(130, new SecureRandom()).toString(32);
        request.getSession().setAttribute("state", state);
        // Fancy way to read index.html into memory, and set the client ID
        // and state values in the HTML before serving it.
        response.getWriter().print(new Scanner(new File("index.html"), "UTF-8")
            .useDelimiter("\\A").next()
            .replaceAll("[{]{2}\\s*CLIENT_ID\\s*[}]{2}", CLIENT_ID)
            .replaceAll("[{]{2}\\s*STATE\\s*[}]{2}", state)
            .replaceAll("[{]{2}\\s*APPLICATION_NAME\\s*[}]{2}",
                APPLICATION_NAME));
        response.setStatus(HttpServletResponse.SC_OK);
      }
      catch (FileNotFoundException e) {
        // When running the quickstart, there was some path issue in finding
        // index.html.  Double check the quickstart guide.
        e.printStackTrace();
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.getWriter().print(e.toString());
      }

    }
  }
}
