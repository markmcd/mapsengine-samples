/**
 * @file This file contains all the JavaScript to authenticate to Google's
 * Maps Engine API servers, and carry out the following steps in publishing
 * GIS style point data as a globally accessible map:
 *
 * Steps:
 *  1: Authenticate using Google Login - @see {@link handleClientLoad}
 *  2: List the Projects that a user can see - @see {@link listProjects}
 *  3: Create a Table to store points in - @see {@link createVectorTable}
 *  4: Insert the capital cities of Australia's states - @see {@link insertTableFeatures}
 *  5: Retrieve above table - @see {link pollTableStatus}
 *  6: Create a layer with above table as source - @see {@link createLayer}
 *  7: Poll layer until processing is finished - @see {@link pollLayerStatus}
 *  8: Publish the layer created above - @see {@link publishLayer}
 *  9: Create a map with the layer as source - @see {@link createMap}
 *  10: Publish map created above - @see {@link publishMap}
 */

/**
 * Enter a client ID for a web application from the Google Developer Console.
 * In your Developer Console project, add a JavaScript origin that corresponds
 * to the domain where you will be running the script.
 *
 * See https://developers.google.com/maps-engine/documentation/register for
 * more detail.
 */
var clientId = 'PLEASE INSERT CLIENT ID HERE';

/**
 * Enter the API key from the Google Developer Console - to handle any
 * unauthenticated requests in the code.
 *
 * See https://developers.google.com/maps-engine/documentation/register for
 * more detail.
 */
var apiKey = 'PLEASE INSERT KEY HERE';

/**
 * This is the authorization scope for Read/Write access to Google Maps Engine.
 *
 * See https://developers.google.com/maps-engine/documentation/oauth/ for more
 * detail.
 */
var scopes = ['https://www.googleapis.com/auth/mapsengine'];

/**
 * Instance of Mapsengine V1. This encapsulates the Google Maps Engine API end point.
 */
var mapsengine = new Mapsengine();

/**
 * This function is called when Google JavaScript client library (gapi.js)
 * loads. This function configures gapi.js with the API Key configured above,
 * and sets up checking for authorization after the page has fully loaded.
 *
 * For full documentation on Google APIs Client Library for JavaScript, please
 * see https://developers.google.com/api-client-library/javascript/reference/referencedocs
 */

function handleClientLoad() {
  if (clientId === 'PLEASE INSERT CLIENT ID HERE') {
    window.alert('Please see the README.md on how to configure this script');
  } else {
    gapi.client.setApiKey(apiKey);
    window.setTimeout(checkAuth, 1);
  }
}

/**
 * This function is called after the page is loaded, and makes gapi confirm if
 * the user has already authorized this application to act on the user's behalf,
 * for the Google Maps Engine Read/Write scope.
 */
function checkAuth() {
  gapi.auth.authorize({ client_id: clientId, scope: scopes, immediate: true },
      handleAuthResult);
}

/**
 * This function is called as a result of checking if the user has already
 * authorised this application. If yes, we make sure the authorize button is
 * hidden, and go ahead with doing our real work. If no, we make sure the
 * authorize button is visible, and configure the button to call this function
 * again when clicked.
 *
 * @param authResult The result of attempting to Authenticate the user.
 */
function handleAuthResult(authResult) {
  var authorizeButton = document.getElementById('authorize-button');
  if (authResult && !authResult.error) {
    authorizeButton.style.visibility = 'hidden';
    listProjects();
  } else {
    authorizeButton.style.visibility = '';
    authorizeButton.onclick = handleAuthClick;
  }
}

/**
 * This function is the non-immediate mode sibling of {@see checkAuth()} above.
 * Where checkAuth confirms if the user has already authorized this application,
 * this call to gapi.auth.authorize will show a pop-up asking the user for
 * authorization for this application to act on the user's behalf with the
 * Google Mapsengine scope.
 *
 * @param event Button click event (ignored).
 * @returns {boolean} Returns false to prevent further processing.
 */
function handleAuthClick(event) {
  gapi.auth.authorize({ client_id: clientId, scope: scopes, immediate: false }, handleAuthResult);
  return false;
}

/**
 * This function loads the Google Mapsengine v1 API discovery document, such
 * that we can use the API directly. Upon successfully loading the Mapsengine
 * API discovery document, we list the projects that the user has. To conform
 * with Mapsengine 1qps limit, we sleep for a second before starting the next
 * step.
 */
function listProjects() {
  mapsengine.projects.list(function(err, response){
    if (err) {
      $('#list-projects-response').text('Error response:\n\n' +
          JSON.stringify(err, null, 2));
      return;
    }
    if (response.projects.length > 0) {
      var select = $('<select id="project-selector">');
      response.projects.forEach(function(project, index, projects) {
        select.append($('<option/>').val(project.id).text(project.name));
      });

      $('#project-name').empty().append(select);
      var goButton = $('#go-button');
      goButton.click(function() {
        createVectorTable($('#project-selector').val());
      });
      goButton.removeAttr('disabled');
    } else {
      window.alert('Sorry, you appear to have no Mapsengine Projects');
    }

    $('#list-projects-response').text(JSON.stringify(response, null, 2));
  });
}

/**
 * The name of the Vector Table we will create in GME.
 */
var tableName = 'Cities of Australia';

/**
 * This is the structure of our vector table of mapsengine data.
 */
var tableSchema = {
  columns: [
    {
      name: 'geometry',
      type: 'points'
    },
    {
      name: 'city',
      type: 'string'
    }
  ]
};

/**
 * This function takes the connection to Maps Engine, the Project ID we found by
 * listing the user's projects, and creates a vector table to insert the data
 * into. Note, the schema we create is configured to the data we are about to
 * insert. If table creation is successful, we wait a second and then insert the
 * table data.
 *
 * @param projectId Project ID of the GME API Project to create table in.
 */
function createVectorTable(projectId) {
  mapsengine.tables.create({
    resource: {
      projectId: projectId,
      name: tableName,
      schema: tableSchema
    }
  }, function(err, response){
    if (err) {
      $('#create-table-response').text('Error response:\n\n' +
          JSON.stringify(err, null, 2));
      return;
    }
    window.setTimeout(function() {
      insertTableFeatures(projectId, response.id);
    }, 1000);
    $('#create-table-response').text(JSON.stringify(response, null, 2));
  });
}

/**
 * The data to insert into the table created above.
 */
var cities = [
  {
    type: 'Feature',
    geometry: {
      type: 'Point',
      coordinates: [
        151.2111,
        -33.8600
      ]
    },
    properties: {
      city: 'Sydney',
      gx_id: 'SYD'
    }
  },
  {
    type: 'Feature',
    geometry: {
      type: 'Point',
      coordinates: [
        144.9631,
        -37.8136
      ]
    },
    properties: {
      city: 'Melbourne',
      gx_id: 'MEL'
    }
  },
  {
    type: 'Feature',
    geometry: {
      type: 'Point',
      coordinates: [
        153.0278,
        -27.4679
      ]
    },
    properties: {
      city: 'Brisbane',
      gx_id: 'BNE'
    }
  },
  {
    type: 'Feature',
    geometry: {
      type: 'Point',
      coordinates: [
        138.6010,
        -34.9290
      ]
    },
    properties: {
      city: 'Adelaide',
      gx_id: 'ADL'
    }
  },
  {
    type: 'Feature',
    geometry: {
      type: 'Point',
      coordinates: [
        115.8589,
        -31.9522
      ]
    },
    properties: {
      city: 'Perth',
      gx_id: 'PER'
    }
  },
  {
    type: 'Feature',
    geometry: {
      type: 'Point',
      coordinates: [
        147.3250,
        -42.8806
      ]
    },
    properties: {
      city: 'Hobart',
      gx_id: 'HBA'
    }
  },
  {
    type: 'Feature',
    geometry: {
      type: 'Point',
      coordinates: [
        149.1244,
        -35.3075
      ]
    },
    properties: {
      city: 'Canberra',
      gx_id: 'CBR'
    }
  },
  {
    type: 'Feature',
    geometry: {
      type: 'Point',
      coordinates: [
        130.8333,
        -12.4500
      ]
    },
    properties: {
      city: 'Darwin',
      gx_id: 'DRW'
    }
  }
];

/**
 * Insert data into a Vector Table, batch style.
 */
function insertTableFeatures(projectId, tableId) {

  mapsengine.tables.features.batchInsert({
    id: tableId,
    resource: {
      features: cities
    }
  }, function (err, response){
    if (err) {
      $('#insert-table-features-response').text('Error response:\n\n' +
          JSON.stringify(err, null, 2));
      return;
    }
    window.setTimeout(function() {
      pollTableStatus(projectId, tableId);
    }, 1000);
    $('#insert-table-features-response').text(JSON.stringify(response, null, 2));
  });
}

/**
 * Retrieve Table details.
 */
function pollTableStatus(projectId, tableId) {
  mapsengine.tables.get({
    id: tableId
  }, function (err, response) {
    if (err) {
      $('#poll-table-status-response').text('Error response:\n\n' +
          JSON.stringify(err, null, 2));
      return;
    }
    window.setTimeout(function() {
      createLayer(projectId, tableId);
    }, 1000);
    $('#poll-table-status-response').text(JSON.stringify(response, null, 2));
  });
}

/**
 * Create a Layer, with the table uploaded above as the datasource, with
 * process=true to kick off processing.
 */
function createLayer(projectId, tableId) {
  mapsengine.layers.create({
    resource: {
      datasourceType: 'table',
      name: 'Capital Cities of Australia Layer',
      projectId: projectId,
      styles: [{
        type: 'displayRule',
        displayRules: [{
          name: 'Default Display Rule',
          zoomLevels: {
            min: 0,
            max: 24
          },
          pointOptions: {
            icon: {
              name: 'gx_small_red'
            }
          }
        }]
      }],
      datasources: [{
        id: tableId
      }]
    },
    process: true
  }, function (err, response) {
    if (err) {
      $('#create-layer-response').text('Error response:\n\n' +
          JSON.stringify(err, null, 2));
      return;
    }
    window.setTimeout(function() {
      pollLayerStatus(projectId, response.id);
    }, 1000);
    $('#create-layer-response').text(JSON.stringify(response, null, 2));
  });
}

/**
 * Poll the created layer, waiting for processingStatus to move
 * from processing to complete.
 */
function pollLayerStatus(projectId, layerId) {
  mapsengine.layers.get({
    id: layerId
  }, function (err, response) {
    if (err) {
      $('#poll-layer-response').text('Error response:\n\n' +
          JSON.stringify(err, null, 2));
      return;
    }
    if (response.processingStatus == 'complete') {
      window.setTimeout(publishLayer, 1000, projectId, layerId);
    } else if (response.processingStatus == 'processing') {
      // Still processing, loop around until the layer processing status
      // is either 'complete' or 'failed'
      window.setTimeout(function() {
        pollLayerStatus(projectId, layerId);
      }, 1000);
    }
    $('#poll-layer-response').text(JSON.stringify(response, null, 2));
  });
}

/**
 * Publish this Layer.
 */
function publishLayer(projectId, layerId) {
  mapsengine.layers.publish({id: layerId}, function (err, response) {
    if (err) {
      $('#publish-layer-response').text('Error response:\n\n' +
          JSON.stringify(err, null, 2));
      return;
    }
    window.setTimeout(function() {
      createMap(projectId, layerId);
    }, 1000);
    $('#publish-layer-response').text(JSON.stringify(response, null, 2));
  });
}

/**
 * Create a Map.
 */
function createMap(projectId, layerId) {

  mapsengine.maps.create({resource:{
    name: 'Capital Cities of Australia Map',
    projectId: projectId,
    contents: [
      {
        type: 'layer',
        id: layerId
      }
    ]
  }}, function(err, response){
    if (err) {
      $('#create-map-response').text('Error response:\n\n' +
          JSON.stringify(err, null, 2));
      return;
    }
    window.setTimeout(function() {
      publishMap(projectId, response.id);
    }, 1000);
    $('#create-map-response').text(JSON.stringify(response, null, 2));
  });
}

/**
 * Publish the Map.
 */
function publishMap(projectId, mapId) {

  mapsengine.maps.publish({
    id: mapId
  }, function (err, response) {
    if (err) {
      // Retry on 409, map wasn't ready to publish
      if (err.status == 409) {
        window.setTimeout(publishMap, 1000, projectId, mapId);
        $('#publish-map-response').text('Retrying until map is published:\n\n' +
            JSON.stringify(err, null, 2));
      } else {
        $('#publish-map-response').text('Error response:\n\n' +
            JSON.stringify(err, null, 2));
      }
      return;
    }
    $('#publish-map-response').text(JSON.stringify(response, null, 2));
  });

}

