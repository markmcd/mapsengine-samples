/**
 * @file This file contains the simplest call one can make to GME API - an 
 * unauthenticated request for features of a published table.
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
var apiKey = 'PLEASE INSERT API KEY HERE';

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
    listTableFeatures()
  }
}

var tableId = "18249228033466778353-15118857445398343972";

/**
 * This function lists the features of a published table.
 */
function listTableFeatures() {
  doRequest({
    path: '/mapsengine/v1/tables/' + tableId + '/features',
    params: {
	version: 'published'
    },
    method: 'GET',
    processRequest: function(requestText) {
      $('#list-table-features-request').text(requestText);
    },
    processResponse: function(response) {
      $('#list-table-features-response').text(JSON.stringify(response, null, 2));
    },
    processErrorResponse: function(response) {
      $('#list-table-features-response').text('Error response:\n\n' +
          JSON.stringify(response, null, 2));
    }
  });
}

function doRequest(args) {
  var retryAttempt = 0;

  var requestText = args.method + ' ' + args.path;
  if (args.body) {
    requestText += '\n\n' + JSON.stringify(args.body, undefined, 2);
  }
  args.processRequest(requestText);

  // The following construct is an immediately executed inline function to
  // enable retrying requests when we encounter rate limiting. It captures
  // the request arguments to doRequest.
  (function doRequestWithBackoff() {
    gapi.client.request({
      path: args.path,
      method: args.method,
      body: args.body,
      params: args.params
    }).then(function(response) {
      args.processResponse(response);
    }, function(failureResponse) {
      if (failureResponse.status == 503 ||
          (failureResponse.result.error.errors[0].reason == 'rateLimitExceeded' ||
           failureResponse.result.error.errors[0].reason == 'userRateLimitExceeded')) {
        if (++retryAttempt > 10) {
          return;
        }

        // Exponential back off, with jitter, ramping up to 20 seconds
        // between retries.
        var backoffSeconds = Math.random() * Math.min(Math.pow(2, retryAttempt), 20);
        window.setTimeout(doRequestWithBackoff, backoffSeconds * 1000);

        return;
      }

      if (args.processErrorResponse) {
        args.processErrorResponse(failureResponse);
      }
    });
  })();
}
