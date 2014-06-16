/*
 * Copyright 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mapsengine.samples.auth;

import com.google.api.client.auth.oauth2.AuthorizationCodeFlow;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.servlet.auth.oauth2.AbstractAuthorizationCodeCallbackServlet;
import com.google.api.client.extensions.servlet.auth.oauth2.AbstractAuthorizationCodeServlet;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.MemoryDataStoreFactory;
import com.google.api.services.mapsengine.MapsEngine;
import com.google.api.services.mapsengine.model.Project;
import com.google.gson.Gson;

import org.mortbay.jetty.Server;
import org.mortbay.jetty.servlet.ServletHandler;
import org.mortbay.jetty.servlet.SessionHandler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

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

  /** Replace this with your application's name. */
  private static final String APPLICATION_NAME = "Google Maps Engine Java Quickstart";


  /** Start the server and set up the URL mappings. */
  public static void main(String[] args) throws Exception {
    Server server = new Server(SERVER_PORT);
    ServletHandler servletHandler = new ServletHandler();
    SessionHandler sessionHandler = new SessionHandler();
    sessionHandler.setHandler(servletHandler);
    server.setHandler(sessionHandler);
    servletHandler.addServletWithMapping(MainServlet.class, "/");
    servletHandler.addServletWithMapping(ProjectsServlet.class, "/projects");
    servletHandler.addServletWithMapping(AuthServlet.class, "/auth");
    servletHandler.addServletWithMapping(CallbackServlet.class, "/oauth2callback");
    server.start();
    server.join();
  }

  /** Main, "index" servlet for the top-level URL, "/". */
  public static class MainServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws
        ServletException, IOException {
      // This check prevents the "/" handler from handling all requests by default
      if (!"/".equals(request.getServletPath())) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return;
      }

      // Flag to pass to the HTML page to tell if the user has authorize.d
      boolean isAuthed = false;
      Credential credential = (Credential) request.getSession().getAttribute("credential");
      if (credential != null) {
        isAuthed = true;
      }

      response.setContentType("text/html");
      try {
        // Serve up index.html and substitute some variables required by the client.
        response.getWriter().print(new Scanner(new File("res/index.html"), "UTF-8")
            .useDelimiter("\\A").next()
            .replaceAll("[{]{2}\\s*IS_AUTHED\\s*[}]{2}", String.valueOf(isAuthed))
            .replaceAll("[{]{2}\\s*APPLICATION_NAME\\s*[}]{2}", APPLICATION_NAME));
        response.setStatus(HttpServletResponse.SC_OK);
      } catch (FileNotFoundException e) {
        // When running the quickstart, there was some path issue in finding
        // index.html.  Double check the quickstart guide.
        e.printStackTrace();
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.getWriter().print(e.toString());
      }

    }

  }

  /** JSON endpoint to retrieve the list of project names. */
  public static class ProjectsServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {

      List<String> projects = new ArrayList<String>();

      // Retrieve the list of projects from Maps Engine.
      Credential credential = (Credential) req.getSession().getAttribute("credential");
      if (credential != null) {
        MapsEngine engine = new MapsEngine.Builder(TRANSPORT, JSON_FACTORY, credential)
            .setApplicationName(APPLICATION_NAME)
            .build();

        // Here's where the magic happens.
        for (Project p : engine.projects().list().execute().getProjects()) {
          projects.add(p.getName());
        }
      }

      // Serialize the list to JSON and output to user.
      resp.setContentType("application/json");
      String json = new Gson().toJson(projects);
      resp.setContentLength(json.length());
      resp.getWriter().print(json);
    }
  }

  /**
   * Thread-safe OAuth 2.0 authorization code flow HTTP servlet.  Note that this is thread-safe,
   * but can only process one request at a time.  For a more performance-critical multi-threaded
   * web application, instead use {@link com.google.api.client.auth.oauth2.AuthorizationCodeFlow}
   * directly.
   */
  public static class AuthServlet extends AbstractAuthorizationCodeServlet {

    /**
     * Loads the authorization code flow to be used across all HTTP servlet requests (only called
     * during the first HTTP servlet request).
     */
    @Override
    protected AuthorizationCodeFlow initializeFlow() throws ServletException, IOException {
      return new GoogleAuthorizationCodeFlow.Builder(
          TRANSPORT,
          JSON_FACTORY,
          clientSecrets.getDetails().getClientId(),
          clientSecrets.getDetails().getClientSecret(),
          Collections.singleton("https://www.googleapis.com/auth/mapsengine.readonly"))
        .setDataStoreFactory(new MemoryDataStoreFactory()).build();
    }

    /** Returns the redirect URI for the given HTTP servlet request. */
    @Override
    protected String getRedirectUri(HttpServletRequest req) throws ServletException, IOException {
      // Using a new GenericUrl based on the request URL will preserve parameters.
      GenericUrl url = new GenericUrl(req.getRequestURL().toString());
      url.setRawPath("/oauth2callback");
      return url.build();
    }

    /** Returns the user ID for the given HTTP servlet request. */
    @Override
    protected String getUserId(HttpServletRequest req) throws ServletException, IOException {
      // You'll need to replace this with the appropriate code to generate user IDs in your system
      return "1234";
    }
  }

  public static class CallbackServlet extends AbstractAuthorizationCodeCallbackServlet {

    /**
     * Loads the authorization code flow to be used across all HTTP servlet requests (only called
     * during the first HTTP servlet request with an authorization code).
     */
    @Override
    protected AuthorizationCodeFlow initializeFlow() throws ServletException, IOException {
      return new GoogleAuthorizationCodeFlow.Builder(
          TRANSPORT,
          JSON_FACTORY,
          clientSecrets.getDetails().getClientId(),
          clientSecrets.getDetails().getClientSecret(),
          Collections.singleton("https://www.googleapis.com/auth/mapsengine.readonly"))
        .setDataStoreFactory(new MemoryDataStoreFactory()).build();
    }

    /** Sends the user back to the auth page when authorization succeeds, saving the credential. */
    @Override
    protected void onSuccess(HttpServletRequest req, HttpServletResponse resp,
        Credential credential) throws ServletException, IOException {
      HttpSession session = req.getSession(true);
      session.setAttribute("credential", credential);
      resp.sendRedirect("/");
    }

    /** Returns the redirect URI for the given HTTP servlet request. */
    @Override
    protected String getRedirectUri(HttpServletRequest req) throws ServletException, IOException {
      // Using a new GenericUrl based on the request URL will preserve parameters.
      GenericUrl url = new GenericUrl(req.getRequestURL().toString());
      url.setRawPath("/oauth2callback");
      return url.build();
    }

    /** Returns the user ID for the given HTTP servlet request. */
    @Override
    protected String getUserId(HttpServletRequest req) throws ServletException, IOException {
      // You'll need to replace this with the appropriate code to generate user IDs in your system
      return "1234";
    }
  }

}
