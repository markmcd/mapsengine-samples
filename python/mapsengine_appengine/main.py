"""Sample app for authenticating and uploading to Google Maps Engine.

Remember to download the OAuth 2.0 client secrets which can be obtained
from the Developer Console <https://code.google.com/apis/console/>
and save them as "client_secrets.json" in the project directory.
"""

__author__ = "jlivni@google.com (Josh Livni)"


import json
import logging
import os
from StringIO import StringIO
import time
from zipfile import ZipFile

import jinja2
import webapp2

from oauth2client import appengine
from oauth2client import client


JINJA_ENVIRONMENT = jinja2.Environment(
    loader=jinja2.FileSystemLoader(os.path.dirname(__file__)),
    autoescape=True,
    extensions=["jinja2.ext.autoescape"])

# CLIENT_SECRETS, name of a file containing the OAuth 2.0 information for this
# application, including client_id and client_secret, which are found
# on the API Access tab on the Google APIs
# Console <http://code.google.com/apis/console>
CLIENT_SECRETS = os.path.join(os.path.dirname(__file__), "client_secrets.json")

# Helpful message to display in the browser if the CLIENT_SECRETS file
# is missing.
CS_TEMPLATE = JINJA_ENVIRONMENT.get_template("templates/missing_client.html")
CS_MESSAGE = CS_TEMPLATE.render({
    "client_secrets": CLIENT_SECRETS
})

decorator = appengine.oauth2decorator_from_clientsecrets(
    CLIENT_SECRETS,
    scope="https://www.googleapis.com/auth/mapsengine",
    message=CS_MESSAGE)


class RenderHandler(webapp2.RequestHandler):
  """Generic handler class with helper methods for others to subclass."""

  def Render(self, template, params):
    """Generic render method to save boilerplate in other get requests.

    Args:
      template: str, The name of the template file.
      params: dict, The variables for the template.
    """

    template = JINJA_ENVIRONMENT.get_template("templates/%s" % template)
    output = template.render(params)
    self.response.write(output)

  @decorator.oauth_required
  def GetHttp(self):
    """Generic method to get authenticated HTTP; used by all but the homescreen.

    Returns:
      Authenticated http request.
    """
    try:
      return decorator.http()
    except client.AccessTokenRefreshError:
      self.redirect("/")


# First page the user hits; ensures proper authentication and redirects.
class MainHandler(RenderHandler):

  @decorator.oauth_aware
  def get(self):
    if decorator.has_credentials():
      self.redirect("/upload")

    params = {
        "url": decorator.authorize_url(),
    }
    self.Render("grant.html", params)


class UploadHandler(RenderHandler):
  """Main class for processing the uploaded shapefile."""

  # Returns a basic form for upload.
  @decorator.oauth_required
  def get(self):
    http = self.GetHttp()
    # get projects list.
    url = "https://www.googleapis.com/mapsengine/v1/projects"
    _, content = http.request(url, method="GET")
    projects = json.loads(content)
    self.Render("upload.html", projects)

  def post(self):
    """Does the actual processing of the upload."""

    base_url = "https://www.googleapis.com/mapsengine/create_tt/tables/"
    # We use a different endpoint for uploading the actual data
    file_url = "https://www.googleapis.com/upload/mapsengine/create_tt/tables/"

    http = self.GetHttp()

    # Ensure json encoding for POST
    headers = {"Content-Type": "application/json"}

    # TODO(jlivni): Don't assume just zip; factor this out and accept csv/kml.
    zipdata = self.request.get("file_obj")

    # try to read zipped data containing folders
    suffixes = ["shp", "dbf", "prj", "shx"]

    # This should be saved to blobstore, and a processing page returned
    # to the user as the files get uploaded.
    opened_file = StringIO(zipdata)
    zipfile = ZipFile(opened_file, "r")

    shapefile_names = [f for f in zipfile.namelist() if f[-3:] in suffixes]
    if len(shapefile_names) < 4:
      raise Exception("missing some shapefiles from %s" % shapefile_names)

    # we have the files, so start with metadata upload.
    filenames = []
    for name in shapefile_names:
      filenames.append({
          "filename": "%s" % name
      })
    base_name = shapefile_names[0][:-4]
    description = self.request.get("description", base_name)
    metadata = {
        "name": base_name,
        "description": description,
        "files": filenames,
        # You need the string value of a valid shared and publiched ACL
        # Check the "Access Lists" section of the Maps Engine UI for a list.
        "sharedAccessList": "Map Editors",
        "sharedPublishedAccessList": "Map Viewers",
        "tags": [base_name, "auto_upload"]
    }

    for tag in self.request.get("tags", "").split(","):
      metadata["tags"].append(tag)

    body = json.dumps(metadata)
    create_url = base_url + "upload?projectId=%s" % self.request.get("projects")

    _, content = http.request(create_url,
                              method="POST",
                              headers=headers,
                              body=body)

    initial_response = json.loads(content)
    if "id" not in initial_response:
      return self.Render("upload.html", {
          "error": initial_response["error"]
      })

    # We have created an empty asset. Get Table ID to upload the actual files.
    table_id = initial_response["id"]
    logging.info(initial_response)

    # TODO(jlivni): Actually upload to BlobStore and send to tasks api.
    # A shapefile is a bunch of files; GME requires these four suffixes.
    for name in shapefile_names:
      url = file_url + "%s/files?filename=%s" % (table_id, name)
      logging.debug("upload url is %s", url)

      with zipfile.open(name) as opened:
        logging.info("uploading %s", name)
        headers = {
            "Content-Type": "application/octet-stream",
            "X-User-IP": "0:0:0:0:0:0:0:2"
        }

        _, content = http.request(url,
                                  method="POST",
                                  headers=headers,
                                  body=opened)
        time.sleep(1)

    opened_file.close()

    # Check everything completed.
    self.redirect("/status?table_id=%s" % table_id)


class StatusHandler(RenderHandler):

  @decorator.oauth_required
  def get(self):
    http = self.GetHttp()

    base_url = "https://www.googleapis.com/mapsengine/create_tt/tables/"
    table_id = self.request.get("table_id")
    _, content = http.request(base_url + table_id, method="GET")
    response = json.loads(content)
    if "id" in response:
      response["cid"] = response["id"].split("-")[0]
    self.Render("status.html", response)


app = webapp2.WSGIApplication([
    ("/", MainHandler),
    ("/upload", UploadHandler),
    ("/status", StatusHandler),
    (decorator.callback_path, decorator.callback_handler()),
], debug=True)
