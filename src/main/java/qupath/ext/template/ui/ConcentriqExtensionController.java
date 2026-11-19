package qupath.ext.template.ui;
 

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.template.ConcentriqExtension;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.UserDirectoryManager;
import qupath.lib.common.ColorTools;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;
import qupath.lib.geom.Point2;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.EllipseROI;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.util.AffineTransformation;
import qupath.lib.roi.GeometryROI;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.LineROI;
import qupath.lib.roi.PointsROI;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.util.Objects;
 
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
 
/**
 * Controller for non-extension menu 
 */
public class ConcentriqExtensionController extends VBox {
    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.template.ui.strings");

    private static final String API_URL = "api/imageSets";
    private static final String IMAGES_API_URL = "api/images";

    // Endpoint for fetching a bearer token scoped to a specific image, used to authenticate
    // against Concentriq's tile server.
    private static final String IAS_TOKEN_URL = "api/v3/auth/ias/token";
    private static final String IAS_FILES_UPLOAD_CREATE_URL = "ias/v1/files/upload/create";
    private static final String IAS_FILES_UPLOAD_MULTIPART_URL_URL = "ias/v1/files/upload/multi-part/url";
    private static final String IAS_FILES_UPLOAD_MULTIPART_COMPLETE_URL = "ias/v1/files/upload/multi-part/complete";
    private static final String IAS_ANALYSES_URL = "ias/v1/analyses";
    private static final String IAS_MARKUP_LAYERS_URL = "ias/v1/markupLayers";
    private static final String IAS_MARKUPS_BULK_URL = "ias/v1/markups/bulk";

    private static final String ANNOTATION_CLASSES_API_URL = "api/v3/annotationClasses";

    private static final String ANNOTATIONS_API_URL = "api/annotations";
    private static final String WHOAMI_API_URL = "api/v3/auth/whoami";
    private static final String USERS_API_URL = "api/users";

    private static final Logger logger = LoggerFactory.getLogger(ConcentriqExtensionController.class);

    private static final String FILTERS_JSON = "{\"archived\":false}";
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final Gson gson = new Gson();
    private static final Gson gsonWithNulls = new GsonBuilder().serializeNulls().create();

    private static final double THUMBNAIL_SIZE = 60;

    private static final String IMPORTED_REPOS_DIR = "cumc-dcpl";

    private static String cachedUsername = null;
    private static final BooleanProperty usernameResolvedProperty = new SimpleBooleanProperty(false);

    /**
     * Minimum fraction (0-1) of a negative region's own area that must actually overlap a
     * candidate positive region for the two to be considered associated at all - below this,
     * the pairing is considered too ambiguous to act on.
     */
    private static final double MINIMUM_NEGATIVE_REGION_OVERLAP_FRACTION = 0.9;

    /**
     * How many pixels to shrink a partially-overlapping negative region inward by, after
     * clipping it to the positive region's boundary, so the two never end up sharing a border
     */
    private static final double NEGATIVE_REGION_CLAMP_MARGIN_PIXELS = 3.0;

    @FXML
    private TableView<ImageTableRow> tableView;
    @FXML
    private TableColumn<ImageTableRow, String> idColumn;
    @FXML
    private TableColumn<ImageTableRow, String> nameColumn;
    @FXML
    private TableColumn<ImageTableRow, String> descriptionColumn;
    @FXML
    private TableColumn<ImageTableRow, String> groupNameColumn;
    @FXML
    private TableColumn<ImageTableRow, String> thumbnailColumn;
    @FXML
    private Button importButton;
    @FXML
    private ProgressIndicator loadingIndicator;
    @FXML
    private TextField searchField;

    private final SimpleBooleanProperty importInProgress = new SimpleBooleanProperty(false);
 
    /**
     * Backing data for the table. Currently empty - this is where rows pulled
     * from the API should be added later, e.g. via {@code getTableData().addAll(...)}.
     */
    private final ObservableList<ImageTableRow> tableData = FXCollections.observableArrayList();
 
    /**
     * Expose the table's backing list so it can be populated (e.g. once API data is available)
     * without needing to touch the controller's FXML wiring.
     * @return the observable list backing the table
     */
    public ObservableList<ImageTableRow> getTableData() {
        return tableData;
    }
 
    /**
     * Create a new instance of the interface controller.
     * @return a new instance of the interface controller
     * @throws IOException If reading the extension FXML files fails.
     */
    public static ConcentriqExtensionController createInstance() throws IOException {
        return new ConcentriqExtensionController();
    }

    public void refreshFromApi() {
        logger.info("in refreshFromApi()");
	loadingIndicator.setVisible(true);
        tableView.setVisible(false);
        searchField.setVisible(false);

        CompletableFuture.supplyAsync(this::fetchAllRows)
                .whenComplete((rows, error) -> Platform.runLater(() -> {
                    loadingIndicator.setVisible(false);
                    tableView.setVisible(true);
                    searchField.setVisible(true);
                    if (error != null) {
                        logger.error("Failed to fetch image table data from API", error);
                        // Leave existing rows in place rather than clearing the table on failure.
                        return;
                    }
                    tableData.setAll(rows);
                }));
    }

    public static List<AnnotationRecord> fetchAnnotations(int imageId) {
        String token = fetchIasToken(imageId);
        if (token == null) {
            logger.warn("Could not fetch IAS auth token for image {} - cannot fetch annotations", imageId);
            return List.of();
        }
 
        String filtersJson = "{\"imageId\":[" + imageId + "]}";
        String uri = stripAllTrailingChars(ConcentriqExtension.concentriqUrlProperty().getValue(), '/') + "/" + ANNOTATIONS_API_URL + "?filters=" + URLEncoder.encode(filtersJson, StandardCharsets.UTF_8);
 
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
 
        try {
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            AnnotationsApiResponse response = gson.fromJson(httpResponse.body(), AnnotationsApiResponse.class);
 
            if (response == null || response.data == null || response.data.annotations == null) {
                logger.warn("No annotations returned from API for image {}", imageId);
                return List.of();
            }
 
            return response.data.annotations;
        } catch (IOException e) {
            logger.error("Failed to fetch annotations for image {}", imageId, e);
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while fetching annotations for image {}", imageId, e);
            return List.of();
        }
    }

    /**
     * Add a single Concentriq image to a project as a new, metadata-only entry (no pixel data
     * fetched at this point - that only happens lazily, when the image is actually opened, via
     * {@link ConcentriqServerBuilder#build()}).
     * @param project the project to add the image to
     * @param image the API record describing the image to add
     * @return true if the image was added, false if it was skipped (not yet in a success state)
     * @throws IOException if adding the image to the project failed
     */
    public static boolean addImageToProject(Project<BufferedImage> project, ImageRecord image) throws IOException {
        String imageName = image.name != null ? image.name : image.slideName;
        logger.info("imageId:", Integer.toString(image.id));
        logger.info("mppx:", Double.toString(image.mppx));
        logger.info("mppy:", Double.toString(image.mppy));

        if (image.status != 2) {
            logger.info("Skipping image not yet in success state:", image.id);
            return false;
        }
        var metadataBuilder = new ImageServerMetadata.Builder()
                .width(image.imgWidth)
                .height(image.imgHeight)
                .pixelType(PixelType.UINT8)
                .rgb(true)
                .name(imageName);
        if (Double.isFinite(image.objectivePower) && image.objectivePower > 0) {
            metadataBuilder = metadataBuilder.magnification(image.objectivePower);
        }

        if (Double.isFinite(image.mppx) && Double.isFinite(image.mppy) && image.mppx > 0 && image.mppy > 0) {
            metadataBuilder = metadataBuilder.pixelSizeMicrons(image.mppx, image.mppy);
        }

        ImageServerMetadata metadata = metadataBuilder.build();

        ProjectImageEntry<BufferedImage> entry = project.addImage(
                new ConcentriqServerBuilder(metadata, image.id, imageName, image.mppx, image.mppy, image.objectivePower));
        entry.setImageName(imageName);
        setThumbnailIfAvailable(entry, image);
        return true;
    }

    public static final String CONCENTRIQ_REPO_ID_METADATA_KEY = "concentriq_repo_id";
    public static final String TARGET_ENVIRONMENT_METADATA_KEY = "target_environment";

    /**
     * Blocking call (intended to run on a background thread) that creates the project directory,
     * fetches every image belonging to the given repo from the API, and adds one metadata-only
     * entry per image before syncing the project to disk.
     * @param repoName the repo's display name, used for the project and (as a fallback) image names
     * @param repoId the repo's ("imageSetId") id, used to filter the images API
     * @param projectDir the directory in which to create the project
     * @return the newly created project
     */
    private Project<BufferedImage> createProjectWithImages(String repoName, int repoId, File projectDir) {
        try {
            Project<BufferedImage> project = Projects.createProject(projectDir, BufferedImage.class);
            project.getMetadata().put(CONCENTRIQ_REPO_ID_METADATA_KEY, String.valueOf(repoId));
            project.getMetadata().put(TARGET_ENVIRONMENT_METADATA_KEY, ConcentriqExtension.concentriqUrlProperty().getValue());

            List<PathClass> annotationClasses = fetchAnnotationClasses(repoId);
            if (annotationClasses.isEmpty()) {
                logger.warn("No annotation classes fetched from Concentriq for repo '{}'", repoName);
            }
            project.setPathClasses(annotationClasses);
 
            List<ImageRecord> images = fetchImagesForRepo(repoId);
            int addedCount = 0;
            for (ImageRecord image : images) {
                if (addImageToProject(project, image)) {
                    addedCount++;
                }
            }
 
            project.syncChanges();
            logger.info("Created QuPath project '{}' at {} with {} image entries", project.getName(), projectDir, addedCount);
            return project;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create project for repo '" + repoName + "' (id " + repoId + ")", e);
        }
    }


    /**
     * Fetch an image's thumbnail from its {@code thumbURL.signedURL} and attach it to the entry,
     * so it shows up next to the image name (e.g. in QuPath's project browser).
     * <p>
     * Unlike the metadata set via {@link ConcentriqServerBuilder}, this genuinely needs real
     * image bytes - there's no "thumbnail-only" placeholder trick available - so failures here
     * (missing URL, network error, undecodable image) are logged and skipped rather than failing
     * the whole import, since a missing thumbnail isn't worth losing the rest of the entry over.
     * @param entry the project entry to attach the thumbnail to
     * @param image the API record for that image, used to find the thumbnail URL
     */
    private static void setThumbnailIfAvailable(ProjectImageEntry<BufferedImage> entry, ImageRecord image) {
        String thumbUrl = image.thumbURL != null ? image.thumbURL.signedURL : null;
        if (thumbUrl == null || thumbUrl.isBlank()) {
            return;
        }
 
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(thumbUrl))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
 
            BufferedImage thumbnail = ImageIO.read(new ByteArrayInputStream(response.body()));
            if (thumbnail == null) {
                logger.warn("Could not decode thumbnail for '{}'", image.name);
                return;
            }
            entry.setThumbnail(thumbnail);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while fetching thumbnail for '{}'", image.name, e);
        } catch (IOException e) {
            logger.warn("Failed to fetch or set thumbnail for '{}'", image.name, e);
        }
    }

 
    /**
     * Fetch every image belonging to a repo (imageSet) from the images API, following pagination
     * the same way {@link #fetchAllRows()} does for repos.
     * @param imageSetId the id of the repo whose images should be fetched
     * @return the images belonging to that repo, across all pages
     */
    public static List<ImageRecord> fetchImagesForRepo(int imageSetId) {
        List<ImageRecord> images = new ArrayList<>();
        String filtersJson = "{\"imageSetId\":[" + imageSetId + "]}";
 
        int page = 1;
        int rowsPerPage = 100;
        int totalRows = Integer.MAX_VALUE; // unknown until the first response comes back
 
        while ((page - 1) * rowsPerPage < totalRows) {
            String paginationJson = gson.toJson(new PaginationRequest(page, rowsPerPage, true, List.of("id")));
 
            String uri = stripAllTrailingChars(ConcentriqExtension.concentriqUrlProperty().getValue(), '/') + "/" + IMAGES_API_URL
                    + "?pagination=" + URLEncoder.encode(paginationJson, StandardCharsets.UTF_8)
                    + "&filters=" + URLEncoder.encode(filtersJson, StandardCharsets.UTF_8);
 
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .header("concentriq-api-key", ConcentriqExtension.concentriqAPIKeyProperty().getValue())
                    .GET()
                    .build();
 
            ImagesApiResponse response;
            try {
                HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                response = gson.fromJson(httpResponse.body(), ImagesApiResponse.class);
            } catch (IOException e) {
                throw new RuntimeException("Failed to call images API on page " + page, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while calling images API on page " + page, e);
            }
 
            if (response == null || response.data == null || response.data.images == null) {
                logger.warn("No images returned from API on page {} for image set {}", page, imageSetId);
                break;
            }
 
            images.addAll(response.data.images);
 
            if (response.meta == null || response.meta.pagination == null || response.meta.pagination.rowsPerPage <= 0) {
                // No pagination info to follow - treat this as the only page.
                break;
            }
            totalRows = response.meta.pagination.totalRows;
            rowsPerPage = response.meta.pagination.rowsPerPage;
            page++;
        }
 
        return images;
    }

    /**
     * Build (and create) a project directory named after the current username, the given repo
     * name, and its id, inside a subdirectory named after the current username, the repo's
     * group, and its group id - itself inside {@link #IMPORTED_REPOS_DIR}, inside QuPath's own
     * user directory (confirmed via {@code UserDirectoryManager.getInstance().getUserPath()} -
     * the same directory shown/editable as "QuPath User Directory" in Preferences).
     * The project's name will match this directory's name.
     * <p>
     * Full structure, when the repo has a real group:
     * {@code <user directory>/<IMPORTED_REPOS_DIR>/<username>-<group name>_<groupId>/<username>-<repo name>_<repoId>/}
     * <p>
     * Full structure, when it doesn't:
     * {@code <user directory>/<IMPORTED_REPOS_DIR>/<username>-ungrouped/<username>-<repo name>_<repoId>/}
     * @param repoName the name to use for the new project directory
     * @param groupName the repo's group name, used for the intermediate directory - falls back
     *                  to {@code "ungrouped"} if null, blank, or otherwise invalid
     * @param groupId the repo's group id - appended to the group directory name (only when a
     *                real group name is present), or ignored entirely if the
     *                group name fell back to "ungrouped"
     * @param repoId the repo's id, appended to the repo directory name
     * @return the new project directory, or null if the username couldn't be determined, the
     *         user directory hasn't been set, or the directory could not be created
     */
    private File promptForProjectDirectory(String repoName, String groupName, String groupId, int repoId) {
        String username = getCachedUsername();
        if (username.equals("<username not found>")) {
            logger.error("Could not determine the current username - cannot create a project");
            Dialogs.showErrorMessage(resources.getString("error"),
                    "Could not determine the current username - check your Concentriq API key/URL settings before importing a repo.");
            return null;
        }
        String sanitizedUsername = username.replaceAll("[\\\\/:*?\"<>|]", "_");

        String sanitizedName = (repoName == null || repoName.isBlank())
                ? "repo"
                : repoName.replaceAll("[\\\\/:*?\"<>|]", "_");
        sanitizedName = sanitizedUsername + "-" + sanitizedName + "_" + repoId;

        String sanitizedGroupName;
        if (groupName == null || groupName.isBlank()) {
            sanitizedGroupName = "ungrouped";
        } else {
            sanitizedGroupName = groupName.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (groupId != null && !groupId.isBlank()) {
                sanitizedGroupName = sanitizedGroupName + "_" + groupId;
            }
        }
        sanitizedGroupName = sanitizedUsername + "-" + sanitizedGroupName;

        Path userPath = UserDirectoryManager.getInstance().getUserPath();
        if (userPath == null) {
            // Confirmed possible directly from QuPath's own source (QPEx.java checks for this
            // same null case) - the user directory may not have been set at all.
            logger.error("QuPath's user directory has not been set - cannot create a project");
            Dialogs.showErrorMessage(resources.getString("error"),
                    "QuPath's user directory has not been set - please set it via Edit > Preferences before importing a repo.");
            return null;
        }
        File parentDir = userPath.resolve(IMPORTED_REPOS_DIR).resolve(sanitizedGroupName).toFile();
        logger.info(parentDir.toString());

        File projectDir = new File(parentDir, sanitizedName);
        if (projectDir.exists()) {
            // A separate, explicit confirmation because current project dir could already contain real analysis work (annotations, detections,
            // measurements) that reusing/overwriting it could lose.
            boolean confirmed = Dialogs.showConfirmDialog("Existing project directory found",
                    "A project directory already exists for this repo:\n" + projectDir + "\n\n"
                            + "Any existing analyses or un-exported annotations in that project directory will be lost if you continue. Continue anyway?");
            if (!confirmed) {
                logger.info("User declined to overwrite existing project directory {}", projectDir);
                return null;
            }
        }
        if (!projectDir.exists() && !projectDir.mkdirs()) {
            logger.error("Failed to create project directory {}", projectDir);
            Dialogs.showErrorMessage(resources.getString("error"), "Unable to create directory " + projectDir);
            return null;
        }
        return projectDir;
    }

    public static String stripAllTrailingChars(String input, char target) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        
        int end = input.length();
        while (end > 0 && input.charAt(end - 1) == target) {
            end--;
        }
        
        return input.substring(0, end).trim();
    }

    /**
     * Handler for the "Import" button.
     * <p>
     * Creates a new QuPath project named after the selected row, and sets it as the active
     * project in the running QuPath instance.
     * <p>
     * TODO: this is just the project-creation part - additional import behavior (e.g. actually
     * adding images from the repo into the new project) still needs to be implemented.
     */
    @FXML
    private void importRepo() {
        ImageTableRow selected = tableView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            // The button is disabled without a selection, but guard anyway.
            return;
        }
 
        String repoName = selected.getName();
        String groupName = selected.getGroupName();
        String groupId = selected.getGroupId();
        int repoId;
        try {
            repoId = Integer.parseInt(selected.getId());
        } catch (NumberFormatException e) {
            logger.error("Selected row's id '{}' is not a valid image set id", selected.getId());
            Dialogs.showErrorMessage(resources.getString("error"), "Selected row has an invalid id");
            return;
        }
 
        File projectDir = promptForProjectDirectory(repoName, groupName, groupId, repoId);
        if (projectDir == null) {
            return;
        }
 
        tableView.setVisible(false);
        searchField.setVisible(false);
        loadingIndicator.setVisible(true);
        importInProgress.set(true);
 
        CompletableFuture.supplyAsync(() -> createProjectWithImages(repoName, repoId, projectDir))
                .whenComplete((project, error) -> Platform.runLater(() -> {
                    loadingIndicator.setVisible(false);
                    tableView.setVisible(true);
                    searchField.setVisible(true);
                    importInProgress.set(false);
 
                    if (error != null) {
                        logger.error("Failed to create QuPath project for '{}'", repoName, error);
                        Dialogs.showErrorMessage(resources.getString("error"), "Unable to create project for " + repoName);
                        return;
                    }
 
                    var qupath = QuPathGUI.getInstance();
                    if (qupath != null) {
                        qupath.setProject(project);
                        // Immediately sync the displayed/available classification list (a
                        // global, preferences-backed list, separate from any specific
                        // project's own pathClasses) to match this project's classes - rather
                        // than waiting for QuPath's own syncProjectPathClassesToAvailable(),
                        // which only fires on certain events (most likely opening an image) and
                        // otherwise leaves the Annotations panel showing whatever was last
                        // active, even for a project that was just freshly created.  Doesn't seem to be working for some reason.
                        qupath.getAvailablePathClasses().setAll(project.getPathClasses());
                    }
                    Dialogs.showInfoNotification(resources.getString("title"), "Created project: " + project.getName());
                }));
    }

    /**
     * Blocking call (intended to run on a background thread) that walks every page of the API
     * response and collects all {@code imageSets} entries into table rows.
     * @return the combined rows across all pages
     */
    private List<ImageTableRow> fetchAllRows() {
        List<ImageTableRow> rows = new ArrayList<>();
 
        int page = 1;
        int rowsPerPage = 100;
        int totalRows = Integer.MAX_VALUE; // unknown until the first response comes back
 
        while ((page - 1) * rowsPerPage < totalRows) {
            String paginationJson = gson.toJson(new PaginationRequest(page, rowsPerPage, true, List.of("id")));
 
            String uri = stripAllTrailingChars(ConcentriqExtension.concentriqUrlProperty().getValue(), '/') + "/" + API_URL
                    + "?pagination=" + URLEncoder.encode(paginationJson, StandardCharsets.UTF_8)
                    + "&filters=" + URLEncoder.encode(FILTERS_JSON, StandardCharsets.UTF_8)
                    + "&includeMetadata=true";
 
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .header("concentriq-api-key", ConcentriqExtension.concentriqAPIKeyProperty().getValue())
                    .GET()
                    .build();
 
            ApiResponse response;
            try {
                HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                response = gson.fromJson(httpResponse.body(), ApiResponse.class);
            } catch (IOException e) {
                throw new RuntimeException("Failed to call API on page " + page, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while calling API on page " + page, e);
            }
 
            if (response == null || response.data == null || response.data.imageSets == null) {
                logger.warn("No data returned from API on page {}", page);
                break;
            }
 
            for (ImageSet imageSet : response.data.imageSets) {
                rows.add(new ImageTableRow(
                        String.valueOf(imageSet.id),
                        imageSet.name,
                        imageSet.description,
                        imageSet.groupName,
                        imageSet.groupId != null ? String.valueOf(imageSet.groupId) : "",
                        imageSet.thumbnailURL));
            }
 
            if (response.meta == null || response.meta.pagination == null || response.meta.pagination.rowsPerPage <= 0) {
                // No pagination info to follow - treat this as the only page.
                break;
            }
            totalRows = response.meta.pagination.totalRows;
            rowsPerPage = response.meta.pagination.rowsPerPage;
            page++;
        }
 
        return rows;
    }

    private List<ImageTableRow> parseTableRows(String responseBody) {
        // Placeholder: no parsing logic yet, so this always returns an empty list.
        logger.info(responseBody);
        return List.of();
    }
 
    /**
     * This method reads an FXML file. These are markup files containing the structure of a UI element.
     * <p>
     * Fields in this class tagged with <code>@FXML</code> correspond to UI elements, and methods tagged with <code>@FXML</code> are methods triggered by actions on the UI (e.g., mouse clicks).
     * <p>
     * We consider the use of FXML to be "best practice" for UI creation, as it separates logic from layout and enables easier use of CSS. However, it is not mandatory, and you could instead define the layout of the UI using code.
     * @throws IOException If the FXML can't be read successfully.
     */
    private ConcentriqExtensionController() throws IOException {
        var url = EditConfigurationModalController.class.getResource("repo_importer_modal.fxml");
        FXMLLoader loader = new FXMLLoader(url, resources);
        loader.setRoot(this);
        loader.setController(this);
        loader.load();
 
        idColumn.setCellValueFactory(new PropertyValueFactory<>("id"));
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        descriptionColumn.setCellValueFactory(new PropertyValueFactory<>("description"));
        groupNameColumn.setCellValueFactory(new PropertyValueFactory<>("groupName"));

        thumbnailColumn.setCellValueFactory(new PropertyValueFactory<>("image"));
        thumbnailColumn.setCellFactory(column -> new ThumbnailCell());
 
        // Tall enough for the thumbnails to render without being clipped.
        tableView.setFixedCellSize(THUMBNAIL_SIZE + 10);
 
        // Wrap tableData in a FilteredList (driven by the search box) and a SortedList (so
        // column-header sorting keeps working correctly on top of the filtered view), then show
        // that combined view instead of the raw list.
        FilteredList<ImageTableRow> filteredData = new FilteredList<>(tableData, row -> true);
        SortedList<ImageTableRow> sortedData = new SortedList<>(filteredData);
        sortedData.comparatorProperty().bind(tableView.comparatorProperty());
        tableView.setItems(sortedData);

        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            String query = newValue == null ? "" : newValue.trim().toLowerCase();
            filteredData.setPredicate(row -> {
                if (query.isEmpty()) {
                    return true;
                }
                String name = row.getName();
                return name != null && name.toLowerCase().contains(query);
            });
        });

        importButton.disableProperty().bind(
                Bindings.or(tableView.getSelectionModel().selectedItemProperty().isNull(), importInProgress));

        refreshFromApi();
    }


    /**
     * Fetch a repo's (imageSet's) annotation classes from Concentriq and convert them into
     * {@link PathClass} instances, for use as a project's available classes.
     * <p>
     * Filtered to the given {@code imageSetId} via the {@code filter} query parameter, confirmed
     * by the user against a real, working example URL:
     * {@code ?filter={"eager":{"$where":{"imageSets.id":165}}}} - note {@code $where} is nested
     * inside {@code eager}, not a sibling key alongside a string {@code eager} value (an earlier,
     * unconfirmed guess had assumed the latter).
     * <p>
     * Archived classes ({@code isArchived: true}) are skipped, on the assumption that a newly
     * created project shouldn't offer classes considered retired. Classes with a missing or
     * unparseable color still get created (just without an explicit color set) rather than
     * being dropped entirely.
     * @param imageSetId the id of the repo (imageSet) whose annotation classes should be fetched
     * @return the fetched classes, or an empty list if the request failed or returned nothing
     */
    private static List<PathClass> fetchAnnotationClasses(int imageSetId) {
        String filterJson = "{\"eager\":{\"$where\":{\"imageSets.id\":" + imageSetId + "}}}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripAllTrailingChars(ConcentriqExtension.concentriqUrlProperty().getValue(), '/') + "/" + ANNOTATION_CLASSES_API_URL
                        + "?filter=" + URLEncoder.encode(filterJson, StandardCharsets.UTF_8)))
                .header("concentriq-api-key", ConcentriqExtension.concentriqAPIKeyProperty().getValue())
                .GET()
                .build();
 
        try {
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            logger.info("Annotation classes response for image set {}: {}", imageSetId, httpResponse.body());
            AnnotationClassesResponse response = gson.fromJson(httpResponse.body(), AnnotationClassesResponse.class);
 
            if (response == null || response.items == null) {
                logger.warn("No annotation classes returned from API for image set {}", imageSetId);
                return List.of();
            }
 
            List<PathClass> pathClasses = new ArrayList<>();
            for (AnnotationClassEntry item : response.items) {
                if (item.isArchived || item.name == null || item.name.isBlank()) {
                    continue;
                }

                PathClass pathClass = PathClass.getInstance(item.name);
                Integer color = parseHexColor(item.color);
                if (color != null) {
                    pathClass.setColor(color);
                }
                pathClasses.add(pathClass);
            }
            return pathClasses;
        } catch (IOException e) {
            logger.error("Failed to fetch annotation classes from API for image set {}", imageSetId, e);
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while fetching annotation classes from API for image set {}", imageSetId, e);
            return List.of();
        }
    }


    /**
     * Clear the cached username, so the next call to {@link #getCachedUsername()} performs a
     * fresh lookup rather than returning a stale value - used when the API key or Concentriq
     * URL changes, since either could mean a genuinely different user/organization is now
     * authenticated.
     */
    public static void clearCachedUsername() {
        cachedUsername = null;
        usernameResolvedProperty.set(false);
    }


    /**
     * Fetch the current API key's associated username (the portion of their email before the
     * "@"), caching it for the rest of the session on first lookup - confirmed with the user
     * that this doesn't need to be refetched even if the API key changes mid-session.
     * <p>
     * Two calls: {@code GET /api/v3/auth/whoami} (a bare {@code {"userId": ...}} response,
     * unlike almost every other endpoint in this project, which use a {@code "meta"}/
     * {@code "data"} envelope) to get the caller's user id, then {@code GET /api/users} (which
     * DOES use the standard envelope) to find that user's email and derive the username from
     * it. The full user list is fetched and searched client-side, rather than attempting a
     * server-side filter - confirmed with the user, since it isn't known whether
     * {@code /api/users} supports the same filter syntax verified for
     * {@code /api/v3/annotationClasses}.
     * @return the cached or newly-fetched username, or {@code "<username not found>"} if either
     *         call fails, or the user id from {@code /whoami} isn't found in the
     *         {@code /api/users} list
     */
    public static String getCachedUsername() {
         if (cachedUsername != null) {
            return cachedUsername;
        }

        Integer userId = fetchCurrentUserId();
        String username = userId != null ? fetchUsernameById(userId) : null;
        cachedUsername = username != null ? username : "<username not found>";
        usernameResolvedProperty.set(username != null);
        return cachedUsername;
    }

    private static Integer fetchCurrentUserId() {
        try {
             HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripAllTrailingChars(ConcentriqExtension.concentriqUrlProperty().getValue(), '/') + "/" + WHOAMI_API_URL))
                .header("concentriq-api-key", ConcentriqExtension.concentriqAPIKeyProperty().getValue())
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                logger.warn("Failed to fetch current user id from /whoami: {} {}", response.statusCode(), response.body());
                return null;
            }
            WhoAmIResponse whoAmI = gson.fromJson(response.body(), WhoAmIResponse.class);
            return whoAmI != null ? whoAmI.userId : null;
        } catch (IOException e) {
            logger.warn("Failed to fetch current user id from /whoami", e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while fetching current user id from /whoami", e);
            return null;
        } catch (Exception e) {
            logger.warn("Some other error encountered while fetching current user if from /whoami", e);
            return null;
        }
    }

    private static String fetchUsernameById(int userId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripAllTrailingChars(ConcentriqExtension.concentriqUrlProperty().getValue(), '/') + "/" + USERS_API_URL))
                .header("concentriq-api-key", ConcentriqExtension.concentriqAPIKeyProperty().getValue())
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                logger.error("Failed to fetch users from /api/users: {} {}", response.statusCode(), response.body());
                return null;
            }
            UsersResponse usersResponse = gson.fromJson(response.body(), UsersResponse.class);
            if (usersResponse == null || usersResponse.data == null || usersResponse.data.users == null) {
                return null;
            }
            for (UserEntry user : usersResponse.data.users) {
                if (user.id == userId && user.email != null && user.email.contains("@")) {
                    return user.email.substring(0, user.email.indexOf('@'));
                }
            }
            return null;
        } catch (IOException e) {
            logger.error("Failed to fetch users from /api/users", e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while fetching users from /api/users", e);
            return null;
        }
    }


    /**
     * Create a markup layer for an existing Concentriq analysis, via
     * {@code POST /ias/v1/markupLayers}, authenticated the same way as
     * {@link #createConcentriqAnalysis} - with the image-scoped bearer token, not the
     * {@code concentriq-api-key} header.
     * <p>
     * The {@code style} block and {@code index} are left as fixed, literal values for now, per
     * the user - not yet configurable. {@code name} is likewise fixed to {@code "Cells"} for now.
     * @param analysisId the id of the analysis (from {@link #createConcentriqAnalysis}) to
     *                   create this markup layer under
     * @param iasToken the image-scoped bearer token to authenticate with
     * @return the newly-created markup layer's own {@code id}, or null if the request failed
     */
    public static Integer createConcentriqMarkupLayer(int analysisId, String name, String strokeColor, String iasToken) {
        MarkupLayerRequestBody requestBody = new MarkupLayerRequestBody(
                analysisId, new MarkupLayerStyle(strokeColor, strokeColor, 2), name, 10);
        String body = gsonWithNulls.toJson(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripAllTrailingChars(ConcentriqExtension.concentriqUrlProperty().getValue(), '/') + "/" + IAS_MARKUP_LAYERS_URL))
                .header("Authorization", "Bearer " + iasToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                logger.error("Failed to create markup layer for analysis {}: {} {}", analysisId, response.statusCode(), response.body());
                return null;
            }
            MarkupLayerCreationResponse markupLayerResponse = gson.fromJson(response.body(), MarkupLayerCreationResponse.class);
            return markupLayerResponse != null ? markupLayerResponse.id : null;
        } catch (IOException e) {
            logger.error("Failed to create markup layer for analysis {}", analysisId, e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while creating markup layer for analysis {}", analysisId, e);
            return null;
        }
    }


    static class MarkupLayerRequestBody {
        int analysisId;
        MarkupLayerStyle style;
        String name;
        int index;

        MarkupLayerRequestBody(int analysisId, MarkupLayerStyle style, String name, int index) {
            this.analysisId = analysisId;
            this.style = style;
            this.name = name;
            this.index = index;
        }
    }

    /** The {@code "style"} sub-object of {@link MarkupLayerRequestBody}: {@code {"fill": ..., "stroke": ...}}. */
    static class MarkupLayerStyle {
        String fill;
        String stroke;
        int penSize;

        MarkupLayerStyle(String fill, String stroke, int penSize) {
            this.fill = fill;
            this.stroke = stroke;
            this.penSize = penSize;
        }
    }

    /**
     * Minimal shape of the {@code POST /ias/v1/markupLayers} response.
     */
    static class MarkupLayerCreationResponse {
        int id;
    }


    /**
     * Mark an existing Concentriq analysis as complete, via
     * {@code PATCH /ias/v1/analyses/:analysisId}
     * @param analysisId the id of the analysis (from {@link #createConcentriqAnalysis}) to mark complete
     * @param iasToken the image-scoped bearer token to authenticate with
     * @return true if the request succeeded, false otherwise
     */
    public static boolean markConcentriqAnalysisComplete(int analysisId, String iasToken) {
        String body = gson.toJson(new AnalysisStatusUpdateRequestBody("Complete"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripAllTrailingChars(ConcentriqExtension.concentriqUrlProperty().getValue(), '/') + "/" + IAS_ANALYSES_URL + "/" + analysisId))
                .header("Authorization", "Bearer " + iasToken)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                logger.error("Failed to mark analysis {} complete: {} {}", analysisId, response.statusCode(), response.body());
                return false;
            }
            return true;
        } catch (IOException e) {
            logger.error("Failed to mark analysis {} complete", analysisId, e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while marking analysis {} complete", analysisId, e);
            return false;
        }
    }

    /** Request body for {@link #markConcentriqAnalysisComplete(int, String)}: {@code {"status": "Complete"}}. */
    static class AnalysisStatusUpdateRequestBody {
        String status;

        AnalysisStatusUpdateRequestBody(String status) {
            this.status = status;
        }
    }


    /**
     * Submit a new analysis entry to Concentriq, via {@code POST /ias/v1/analyses}.
     * @param imageId the Concentriq image id, sent as {@code image.id}
     * @param storageKey the image's storage key (from {@link ImageDetails#storageKey}), sent as
     *                   {@code image.originalImage}
     * @param iasToken the image-scoped bearer token to authenticate with
     * @return the raw JSON response body on success, or null if the request failed
     */
    public static Integer createConcentriqAnalysis(int imageId, String storageKey, int imageWidth, int imageHeight, double mppx, double mppy, String username, String iasToken) {
        AnalysisSubmissionRequestBody requestBody = new AnalysisSubmissionRequestBody(
            Integer.valueOf(ConcentriqExtension.analysisResultsAppIdProperty().getValue()), "Processing", "Qupath Analysis Results Submission",
            new AnalysisImageRef(imageId, storageKey, imageWidth, imageHeight, mppx, mppy),
                    Map.of(), Map.of(ConcentriqExtension.analysisRequestorFieldKeyProperty().getValue(), username));
        String body = gson.toJson(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripAllTrailingChars(ConcentriqExtension.concentriqUrlProperty().getValue(), '/') + "/" + IAS_ANALYSES_URL))
                .header("Authorization", "Bearer " + iasToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                logger.error("Failed to submit analysis results for image {}: {} {}", imageId, response.statusCode(), response.body());
                return null;
            }
            AnalysisCreationResponse analysisCreationResponse = gson.fromJson(response.body(), AnalysisCreationResponse.class);
            return analysisCreationResponse != null ? analysisCreationResponse.id : null;
        } catch (IOException e) {
            logger.error("Failed to submit analysis results for image {}", imageId, e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while submitting analysis results for image {}", imageId, e);
            return null;
        }
    }

    /**
     * Request body for {@link #submitAnalysisResults(int, String, String)}:
     * {@code {"analysisAppId": ..., "status": ..., "name": ..., "image": {...}, "caseMetadata": {}}}.
     */
    static class AnalysisSubmissionRequestBody {
        int analysisAppId;
        String status;
        String name;
        AnalysisImageRef image;
        Map<String, Object> caseMetadata;
        Map<String, Object> userDefinedFields;

        AnalysisSubmissionRequestBody(int analysisAppId, String status, String name, AnalysisImageRef image, Map<String, Object> caseMetadata, Map<String, Object> userDefinedFields) {
            this.analysisAppId = analysisAppId;
            this.status = status;
            this.name = name;
            this.image = image;
            this.caseMetadata = caseMetadata;
            this.userDefinedFields = userDefinedFields;
        }
    }

    /** The {@code "image"} sub-object of {@link AnalysisSubmissionRequestBody}: {@code {"id": ..., "originalImage": ...}}. */
    static class AnalysisImageRef {
        int id;
        String originalImage;
        int width;
        int height;
        double mppx;
        double mppy;

        AnalysisImageRef(int id, String originalImage, int width, int height, double mppx, double mppy) {
            this.id = id;
            this.originalImage = originalImage;
            this.width = width;
            this.height = height;
            this.mppx = mppx;
            this.mppy = mppy;
        }
    }

    /**
     * Minimal shape of the {@code POST /ias/v1/analyses} response, just enough for Gson to map
     * the one field {@link #createConcentriqAnalysis} actually needs - the response is a bare
     * object (no "meta"/"data" envelope), echoing back the submitted fields alongside
     * server-generated ones like this id, confirmed directly by the user from a real response.
     */
    static class AnalysisCreationResponse {
        int id;
    }


    /**
     * Bare (no {@code "meta"}/{@code "data"} envelope, unlike almost every other endpoint in
     * this class) shape of the {@code GET /api/v3/auth/whoami} response.
     */
    private static class WhoAmIResponse {
        int userId;
    }

    /** Shape of the {@code GET /api/users} response. */
    private static class UsersResponse {
        UsersData data;
    }

    private static class UsersData {
        List<UserEntry> users;
    }

    private static class UserEntry {
        int id;
        String email;
    }

    
    /**
     * Fetch a bearer token scoped to a specific image, for use authenticating against
     * Concentriq's tile server.
     * <p>
     * TODO: the exact response field name here is a guess based on inconsistent examples in the
     * integration guide ("token" vs "apiKey" in different sections) - {@link TokenResponse}
     * checks both, but this should be confirmed against the real response once available.
     * @param imageId the id of the image to scope the token to
     * @return the bearer token, or null if the request failed or the response didn't contain one
     */
    public static String fetchIasToken(int imageId) {
        String body = gson.toJson(new TokenRequestBody(imageId));
 
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripAllTrailingChars(ConcentriqExtension.concentriqUrlProperty().getValue(), '/') + "/" + IAS_TOKEN_URL))
                .header("concentriq-api-key", ConcentriqExtension.concentriqAPIKeyProperty().getValue())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
 
        try {
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            TokenResponse response = gson.fromJson(httpResponse.body(), TokenResponse.class);
            return response != null ? (response.token != null ? response.token : response.apiKey) : null;
        } catch (IOException e) {
            logger.error("Failed to fetch IAS auth token for image {}", imageId, e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while fetching IAS auth token for image {}", imageId, e);
            return null;
        }
    }


    /**
     * Fetch the organization's annotation classes from Concentriq and build a lookup from class
     * name (lowercased) to Concentriq annotation class id - used when exporting a dirty
     * annotation's classification back to Concentriq via {@code annotationClassId}.
     * @return a map from lowercased class name to Concentriq annotation class id, or an empty
     *         map if the request failed or returned nothing
     */
    public static Map<String, Integer> fetchAnnotationClassIdsByName() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripAllTrailingChars(ConcentriqExtension.concentriqUrlProperty().getValue(), '/') + "/" + ANNOTATION_CLASSES_API_URL))
                .header("concentriq-api-key", ConcentriqExtension.concentriqAPIKeyProperty().getValue())
                .GET()
                .build();

        try {
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            AnnotationClassesResponse response = gson.fromJson(httpResponse.body(), AnnotationClassesResponse.class);

            if (response == null || response.items == null) {
                logger.warn("No annotation classes returned from API");
                return Map.of();
            }

            Map<String, Integer> idsByName = new HashMap<>();
            for (AnnotationClassEntry item : response.items) {
                if (item.name == null || item.name.isBlank()) {
                    continue;
                }
                idsByName.put(item.name.toLowerCase(), item.id);
            }
            return idsByName;
        } catch (IOException e) {
            logger.error("Failed to fetch annotation class ids from API", e);
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while fetching annotation class ids from API", e);
            return Map.of();
        }
    }


    /**
     * Import annotations from an XML document
     * onto a specific image in Concentriq, via {@code POST /images/:imageId/annotations/import}.
     * That posts the XML as a {@code multipart/form-data} file upload under a field named
     * {@code "files"} - not a raw string body, and not JSON. {@link HttpClient} has no built-in
     * multipart support, so this builds that request body by hand to match.
     * @param imageId the Concentriq image id to import annotations onto
     * @param annotationsXml the XML document to import
     * @return the raw JSON response body on success, or null if the request failed
     */
    public static String exportAnnotationsToConcentriqInXml(int imageId, String annotationsXml) {
        String boundary = "----QuPathBoundary" + UUID.randomUUID();
        String url = stripAllTrailingChars(ConcentriqExtension.concentriqUrlProperty().getValue(), '/') + "/" + IMAGES_API_URL + "/" + imageId + "/annotations/import";

        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files\"; filename=\"annotations.xml\"\r\n"
                + "Content-Type: text/xml\r\n"
                + "\r\n"
                + annotationsXml + "\r\n"
                + "--" + boundary + "--\r\n";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("concentriq-api-key", ConcentriqExtension.concentriqAPIKeyProperty().getValue())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                logger.error("Failed to import annotations for image {}: {} {}", imageId, response.statusCode(), response.body());
                return null;
            }
            String respBody = response.body();
            logger.info("Annotations export response: {}", respBody);
            return respBody;
        } catch (IOException e) {
            logger.error("Failed to import annotations for image {}", imageId, e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while importing annotations for image {}", imageId, e);
            return null;
        }
    }


    /**
     * Convert a single-point ROI's coordinate back into Concentriq's "pin" shapeString format -
     * the inverse of {@link #parsePinRoi}'s normalized-to-pixel conversion. Uses the same
     * (quirky) inverse formula as {@link #polygonRoiToShapeString} - see its javadoc for why.
     * <p>
     * Unlike {@code "free"}'s comma-separated-pair format, {@code "pin"}'s shapeString is just
     * two space-separated numbers, {@code "x y"}, matching {@link #parsePinRoi}'s own read-side
     * format exactly.
     * <p>
     * A {@link qupath.lib.roi.PointsROI} can technically hold more than one point, but "pin"
     * annotations from Concentriq only ever have exactly one (matching {@link #parsePinRoi}'s
     * own single-point construction) - if more than one point is present, only the first is
     * used, with a warning logged, since there's no way to represent multiple points in this
     * shapeString format anyway.
     * @param roi the points ROI to convert
     * @param imageWidth the image's width in pixels
     * @return the resulting shapeString, e.g. {@code "1586.03 4479.23"}, or null if the ROI has
     *         no points at all
     */
    public static String pointsRoiToShapeString(ROI roi, int imageWidth) {
        List<Point2> points = roi.getAllPoints();
        if (points.isEmpty()) {
            logger.warn("Cannot convert an empty points ROI to a shapeString");
            return null;
        }
        if (points.size() > 1) {
            logger.warn("Points ROI has {} points - only the first will be used, since Concentriq's " +
                    "\"pin\" shapeString format only supports a single point", points.size());
        }

        Point2 point = points.get(0);
        double rawX = (point.getX() * 10000) / imageWidth;
        double rawY = (point.getY() * 10000) / imageWidth;
        return rawX + " " + rawY;
    }


    /**
     * Convert an ellipse ROI's bounding box back into Concentriq's "ellipse" shapeString format
     * - the inverse of {@link #parseEllipseRoi}'s normalized-to-pixel conversion.
     * <p>
     * Since {@link #parseEllipseRoi} reuses the exact same {@link #parseBoxCoordinates} logic as
     * {@link #parseRectRoi} (an ellipse is represented by its bounding box, same as a rectangle -
     * confirmed for the read side, and again separately when adding ellipse support to
     * {@code geojsonToXml}'s export side, where a real sample's second vertex was confirmed to
     * equal the first plus the ROI's own width/height), this uses the identical formula to
     * {@link #rectangleRoiToShapeString} - both x/width and y/height divided back out using
     * {@code imageWidth}, not {@code imageHeight} for y/height.
     * @param roi the ellipse ROI to convert
     * @param imageWidth the image's width in pixels
     * @return the resulting shapeString, e.g. {@code "601.45 2931.42 2668.46 3121.21"}
     */
    public static String ellipseRoiToShapeString(ROI roi, int imageWidth) {
        double rawX = (roi.getBoundsX() * 10000) / imageWidth;
        double rawY = (roi.getBoundsY() * 10000) / imageWidth;
        double rawWidth = (roi.getBoundsWidth() * 10000) / imageWidth;
        double rawHeight = (roi.getBoundsHeight() * 10000) / imageWidth;
        return rawX + " " + rawY + " " + rawWidth + " " + rawHeight;
    }


     public static String lineRoiToShapeString(ROI roi, int imageWidth) {
        List<Point2> points = roi.getAllPoints();
        if (points.size() != 2) {
            logger.warn("Cannot convert a line ROI to a shapeString - expected exactly 2 points, got {}", points.size());
            return null;
        }

        // Reversed relative to getAllPoints()'s own order - see javadoc above.
        Point2 first = points.get(0);
        Point2 second = points.get(1);

        double rawX1 = (first.getX() * 10000) / imageWidth;
        double rawY1 = (first.getY() * 10000) / imageWidth;
        double rawX2 = (second.getX() * 10000) / imageWidth;
        double rawY2 = (second.getY() * 10000) / imageWidth;
        return rawX1 + " " + rawY1 + " " + rawX2 + " " + rawY2;
    }


     /**
     * Convert a polygon-shaped ROI's vertices back into Concentriq's "free" shapeString format
     * - the inverse of {@link #parseFreeRoi}'s normalized-to-pixel conversion.
     * <p>
     * Confirmed with the user that the correct inverse uses the same (quirky) formula
     * {@link #parseFreeRoi} actually applies, not the "intended" one - both x and y get divided
     * back out using {@code imageWidth} (not {@code imageHeight} for y), since that's the
     * scaling Concentriq's own data was shown to actually use on the way in.
     * <p>
     * Only handles a single ring (no holes), matching {@link #parseFreeRoi}'s own read-side
     * limitation - this is deliberately scoped to plain, hole-free polygons for now.
     * @param roi the ROI whose vertices should be converted (works for any ROI exposing
     *            {@link ROI#getAllPoints()} - a {@link qupath.lib.roi.PolygonROI} for this use case)
     * @param imageWidth the image's width in pixels
     * @return the resulting shapeString, e.g. {@code "1172.888,1717.114 1199.305,1776.615 ..."}
     */
    public static String polygonRoiToShapeString(ROI roi, int imageWidth) {
        StringBuilder shapeString = new StringBuilder();
        List<Point2> points = roi.getAllPoints();
        for (int i = 0; i < points.size(); i++) {
            Point2 point = points.get(i);
            double rawX = (point.getX() * 10000) / imageWidth;
            double rawY = (point.getY() * 10000) / imageWidth; // imageWidth, not imageHeight - see javadoc above
            if (i > 0) {
                shapeString.append(" ");
            }
            shapeString.append(rawX).append(",").append(rawY);
        }
        return shapeString.toString();
    }


    /**
     * Convert a rectangle ROI's bounds back into Concentriq's "rect" shapeString format - the
     * inverse of {@link #parseBoxCoordinates}'s normalized-to-pixel conversion. Uses the same
     * (quirky) inverse formula as {@link #polygonRoiToShapeString}/{@link #pointsRoiToShapeString}
     * - both x/width and y/height get divided back out using {@code imageWidth}, not
     * {@code imageHeight} for y/height, matching the same scaling Concentriq's own data was
     * shown to actually use on the way in.
     * <p>
     * Unlike {@code "free"}'s comma-separated-pair format, {@code "rect"}'s shapeString is four
     * space-separated numbers, {@code "x y width height"}, matching {@link #parseBoxCoordinates}'s
     * own read-side format exactly (originally confirmed by {@code boundsString} being identical
     * to {@code shapeString} for a real "rect" example, meaning the third/fourth values are
     * already width/height directly, not a second corner point).
     * @param roi the rectangle ROI to convert
     * @param imageWidth the image's width in pixels
     * @return the resulting shapeString, e.g. {@code "601.45 2931.42 2668.46 3121.21"}
     */
    public static String rectangleRoiToShapeString(ROI roi, int imageWidth) {
        double rawX = (roi.getBoundsX() * 10000) / imageWidth;
        double rawY = (roi.getBoundsY() * 10000) / imageWidth;
        double rawWidth = (roi.getBoundsWidth() * 10000) / imageWidth;
        double rawHeight = (roi.getBoundsHeight() * 10000) / imageWidth;
        return rawX + " " + rawY + " " + rawWidth + " " + rawHeight;
    }

    /**
     * Update an existing Concentriq annotation's shapeString, name, and, optionally, its
     * {@code annotationClassId}.
     * @param annotationId the Concentriq annotation id to update
     * @param shapeString the new shapeString value
     * @param annotationClassId the new Concentriq annotation class id, or null to leave the
     *                          annotation's class untouched - Gson's default null-omission
     *                          behavior means a null value here simply isn't included in the
     *                          request body at all, rather than being sent as an explicit null
     * @param text holds the annotation's QuPath name or null to omit it from the request
     *             entirely, callers wanting this field present
     *             even for an unnamed annotation should pass {@code ""} rather than null, since
     *             null here means "omit", not "send empty"
     * @return the raw JSON response body on success, or null if the request failed
     */
    public static String updateAnnotationShapeString(int annotationId, String shapeString, Integer annotationClassId, String text) {
        String url = stripAllTrailingChars(ConcentriqExtension.concentriqUrlProperty().getValue(), '/') + "/" + ANNOTATIONS_API_URL + "/" + annotationId;
        String body = gson.toJson(new UpdateAnnotationShapeRequestBody(shapeString, annotationClassId, text));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("concentriq-api-key", ConcentriqExtension.concentriqAPIKeyProperty().getValue())
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                logger.error("=== REQUEST DETAILS ===");
                logger.error("Request URL : " + request.uri());
                logger.error("Request Method: " + request.method());
                logger.error("Request Body  : " + body);
                logger.error("=======================\n");
                logger.error("Failed to update shapeString for annotation {}: {} {}", annotationId, response.statusCode(), response.body());
                return null;
            }

            logger.info("Sucessfully sent request to cq for update");
            return response.body();
        } catch (IOException e) {
            logger.error("Failed to update shapeString for annotation {}", annotationId, e);
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String stackTraceString = sw.toString();
            logger.info(stackTraceString);
            return null; 
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while updating shapeString for annotation {}", annotationId, e);
            return null;
        }
    }

    /**
     * Request body for {@link #updateAnnotationShapeString(int, String, Integer, String)}:
     * {@code {"shapeString": ..., "annotationClassId": ..., "text": ...}} - any of
     * {@code annotationClassId}/{@code text} left out entirely if null, since Gson omits null
     * fields by default rather than serializing them as explicit nulls.
     */
    private static class UpdateAnnotationShapeRequestBody {
        String shapeString;
        Integer annotationClassId;
        String text;

        UpdateAnnotationShapeRequestBody(String shapeString, Integer annotationClassId, String text) {
            this.shapeString = shapeString;
            this.annotationClassId = annotationClassId;
            this.text = text;
        }
    }


    /**
     * Fetch a single Concentriq annotation's current shapeString, via
     * {@code GET /api/annotations/:annotationId}, authenticated the same way as
     * {@link #updateAnnotationShapeString} (confirmed with the user for that endpoint's PATCH;
     * this GET succeeding with that same header is what confirmed it also applies here).
     * <p>
     * Response envelope confirmed against a real sample: {@code {"meta": {...}, "data": {...}}},
     * with {@code shapeString} nested under {@code "data"} - not top-level.
     * @param annotationId the Concentriq annotation id to fetch
     * @return the annotation's current shapeString, or null if the request failed or the field
     *         couldn't be found in the response
     */
    public static String fetchAnnotationShapeString(int annotationId) {
        String url = stripAllTrailingChars(ConcentriqExtension.concentriqUrlProperty().getValue(), '/') + "/" + ANNOTATIONS_API_URL + "/" + annotationId;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("concentriq-api-key", ConcentriqExtension.concentriqAPIKeyProperty().getValue())
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                logger.error("Failed to fetch shapeString for annotation {}: {} {}", annotationId, response.statusCode(), response.body());
                return null;
            }

            JsonObject responseBody = gson.fromJson(response.body(), JsonObject.class);
            if (!responseBody.has("data") || !responseBody.get("data").isJsonObject()) {
                logger.warn("Could not find 'data' object in response for annotation {}: {}", annotationId, response.body());
                return null;
            }
            JsonObject data = responseBody.getAsJsonObject("data");
            if (!data.has("shapeString")) {
                logger.warn("Could not find 'shapeString' in response data for annotation {}: {}", annotationId, response.body());
                return null;
            }
            return data.get("shapeString").getAsString();
        } catch (IOException e) {
            logger.error("Failed to fetch shapeString for annotation {}", annotationId, e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while fetching shapeString for annotation {}", annotationId, e);
            return null;
        }
    }


    /**
     * Parse a {@code contained_negative_concentriq_regions} metadata value (a JSON array of
     * Concentriq annotation ids, as set by {@link #toPathObjects}) back into a list of ids.
     * @param json the metadata value, e.g. {@code "[48058,61042]"}
     * @return the parsed list of annotation ids
     */
    public static List<Integer> parseContainedNegativeRegionIds(String json) {
        return gson.fromJson(json, new TypeToken<List<Integer>>(){}.getType());
    }


    /**
     * Compute the new shapeString to write back for a dirty {@code GeometryROI}'s parent
     * annotation, accounting for its negative regions.
     * <pre>
     * result = editedRoi's geometry (the parent as currently edited in QuPath - already has
     *          every hole excluded, since that's how QuPath displays a GeometryROI)
     * for each negative region N_i (by its CURRENT Concentriq shapeString):
     *     result = result UNION (originalParentGeometry INTERSECT N_i)
     * </pre>
     * {@code originalParentGeometry} is built from the parent's own current (pre-edit)
     * Concentriq shapeString - not the edited QuPath ROI - since Concentriq stores the positive
     * and negative regions as separate annotations, while QuPath only ever shows the final,
     * already-excluded combined result. Only the portion of each hole that was actually part of
     * the original parent gets added back (not the whole hole, which could extend beyond the
     * original parent's boundary).
     * <p>
     * @param editedRoi the dirty GeometryROI as currently edited in QuPath
     * @param originalParentShapeString the parent's own current shapeString, as fetched fresh
     *                                  from Concentriq (not derived from the QuPath ROI)
     * @param negativeRegionShapeStrings each contained negative region's current shapeString, as
     *                                   fetched fresh from Concentriq, in any order
     * @param imageWidth the image's width in pixels
     * @param imageHeight the image's height in pixels
     * @return the resulting shapeString to write back, or null if any input failed to parse or
     *         the computed result was empty
     */
    public static String computeUpdatedGeometryRoiShapeString(ROI editedRoi, String originalParentShapeString,
                                                               List<String> negativeRegionShapeStrings,
                                                               int imageWidth, int imageHeight) {
        double aspectRatio = imageHeight / (float) imageWidth;

        ROI originalParentRoi = parseFreeRoiFromRawShapeString(originalParentShapeString, imageWidth, imageHeight, aspectRatio);
        if (originalParentRoi == null) {
            logger.error("Could not parse the original parent shapeString '{}'", originalParentShapeString);
            return null;
        }
        var originalParentGeometry = dissolveIfCollection(GeometryTools.roiToGeometry(originalParentRoi));

        var result = dissolveIfCollection(GeometryTools.roiToGeometry(editedRoi));
        logger.info("here1");
        for (String negativeRegionShapeString : negativeRegionShapeStrings) {
            logger.info("iteration");
            ROI negativeRegionRoi = parseFreeRoiFromRawShapeString(negativeRegionShapeString, imageWidth, imageHeight, aspectRatio);
            if (negativeRegionRoi == null) {
                logger.error("Could not parse negative region shapeString '{}'", negativeRegionShapeString);
                return null;
            }
            var negativeRegionGeometry = dissolveIfCollection(GeometryTools.roiToGeometry(negativeRegionRoi));
            logger.info("this1");

            var restoredPortion = dissolveIfCollection(originalParentGeometry.intersection(negativeRegionGeometry));
            logger.info("this2");
            logger.info("result class: {}, restoredPortion class: {}", result.getClass().getSimpleName(), restoredPortion.getClass().getSimpleName());
            result = dissolveIfCollection(GeometryTools.homogenizeGeometryCollection(result.union(restoredPortion)));
            logger.info("this3");
        }

        logger.info("past loop");
        ROI resultRoi = GeometryTools.geometryToROI(result, ImagePlane.getDefaultPlane());
        List<Point2> resultPoints = resultRoi.getAllPoints();
        if (resultPoints.isEmpty()) {
            logger.error("Computed geometry for GeometryROI update was empty - not writing back");
            return null;
        }
        if (!(resultRoi instanceof PolygonROI)) {
            // Confirmed with the user: use just the first ("0th") coordinate set/ring in this
            // case, rather than getAllPoints()'s result, which would flatten every ring/part
            // together into one nonsensical shapeString connecting unrelated boundaries.
            logger.warn("Computed geometry for GeometryROI update is a {} rather than a simple polygon - " +
                    "using its first ring/part only", resultRoi.getClass().getSimpleName());
            return firstRingToShapeString(result, imageWidth);
        }

        return polygonRoiToShapeString(resultRoi, imageWidth);
    }

    /**
     * Parse a raw {@code "free"}-format shapeString (not wrapped in a full
     * {@link AnnotationRecord}, e.g. one fetched directly via
     * {@link #fetchAnnotationShapeString}) into a {@link ROI} - by constructing a minimal
     * temporary {@code AnnotationRecord} and reusing {@link #parseFreeRoi} directly, rather than
     * duplicating its parsing logic.
     */
    private static ROI parseFreeRoiFromRawShapeString(String shapeString, int imageWidth, int imageHeight, double aspectRatio) {
        AnnotationRecord tempRecord = new AnnotationRecord();
        tempRecord.shapeString = shapeString;
        return parseFreeRoi(tempRecord, imageWidth, imageHeight, aspectRatio);
    }


    /**
     * Fetch a single image's details: its {@code imageServerUrl} (the IIIF {@code info.json}
     * endpoint), whether it's a fluorescence image, and its "macro"/"label" associated image
     * URLs (if present) - or null if the request failed or no usable {@code imageSources} entry
     * was found.
     * <p>
     * {@code imageData.imageSources} is a map keyed by an arbitrary numeric source id (one entry
     * per z-layer/channel), rather than a fixed field name - this just takes whichever entry
     * comes first. For fluorescence images specifically, every channel's entry has been observed
     * to share the exact same {@code imageServerUrl} - so this only affects which entry's other
     * fields (name, etc.) get used, not which URL is fetched.
     * <p>
     * A real response has been observed to contain a duplicate {@code "label"} entry (same type,
     * same URL) - only the first occurrence of each type is kept, confirmed with the user, rather
     * than letting a later duplicate silently overwrite it.
     * @param imageId the id of the image to fetch details for
     * @return the image's details, or null if unavailable
     */
    public static ImageDetails fetchImageDetails(int imageId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripAllTrailingChars(ConcentriqExtension.concentriqUrlProperty().getValue(), '/') + "/" + IMAGES_API_URL + "/" + imageId))
                .header("concentriq-api-key", ConcentriqExtension.concentriqAPIKeyProperty().getValue())
                .GET()
                .build();

        try {
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            ImageDetailResponse response = gson.fromJson(httpResponse.body(), ImageDetailResponse.class);

            if (response == null || response.data == null || response.data.imageData == null
                    || response.data.imageData.imageSources == null || response.data.imageData.imageSources.isEmpty()) {
                logger.warn("No imageServerUrl found for image {}", imageId);
                return null;
            }

            boolean isFluorescence = response.data.imageData.fluorescenceChannels != null
                    && !response.data.imageData.fluorescenceChannels.isEmpty();

            String macroImageUrl = null;
            String labelImageUrl = null;
            if (response.data.associatedImages != null) {
                for (ThumbUrl associatedImage : response.data.associatedImages) {
                    if (associatedImage.type == null) {
                        continue;
                    }
                    if ("macro".equals(associatedImage.type) && macroImageUrl == null) {
                        macroImageUrl = associatedImage.signedURL;
                    } else if ("label".equals(associatedImage.type) && labelImageUrl == null) {
                        labelImageUrl = associatedImage.signedURL;
                    }
                }
            }

            ImageSourceEntry firstSource = response.data.imageData.imageSources.values().iterator().next();
            return new ImageDetails(firstSource.imageServerUrl, isFluorescence, macroImageUrl, labelImageUrl, response.data.storageKey);
        } catch (IOException e) {
            logger.error("Failed to fetch image details for image {}", imageId, e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while fetching image details for image {}", imageId, e);
            return null;
        }
    }

    /**
     * Result of {@link #fetchImageDetails(int)}: the image's IIIF URL, its fluorescence flag,
     * and its "macro"/"label" associated image URLs (either may be null if not present).
     */
    public static class ImageDetails {
        public final String imageServerUrl;
        public final boolean isFluorescence;
        public final String macroImageUrl;
        public final String labelImageUrl;
        public final String storageKey;

        ImageDetails(String imageServerUrl, boolean isFluorescence, String macroImageUrl, String labelImageUrl, String storageKey) {
            this.imageServerUrl = imageServerUrl;
            this.isFluorescence = isFluorescence;
            this.macroImageUrl = macroImageUrl;
            this.labelImageUrl = labelImageUrl;
            this.storageKey = storageKey;
        }
    }

    /** Request body shape for {@link #fetchIasToken(int)}: {@code {"imageId": ...}}. */
    static class TokenRequestBody {
        int imageId;
 
        TokenRequestBody(int imageId) {
            this.imageId = imageId;
        }
    }
 
    /**
     * Response shape for {@link #fetchIasToken(int)}. Both fields are mapped since the
     * integration guide shows the token under different keys in different examples.
     */
    static class TokenResponse {
        String token;
        String apiKey;
    }


    public static boolean createConcentriqMarkups(int layerId, List<ConcentriqExtension.CellNucleusRoi> nucleusRois, String iasToken) {
        List<MarkupRequestBody> requestBody = new ArrayList<>();
        for (int index = 0; index < nucleusRois.size(); index++) {
            ConcentriqExtension.CellNucleusRoi nucleusRoi = nucleusRois.get(index);
            List<MarkupShapePoint> shapePoints = new ArrayList<>();
            for (Point2 point : nucleusRoi.points()) {
                shapePoints.add(new MarkupShapePoint(point.getX(), point.getY()));
            }
            requestBody.add(new MarkupRequestBody(layerId, index, new MarkupShape(shapePoints, "polygon"), Map.of()));
        }
        String body = gson.toJson(requestBody);
        logger.info(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripAllTrailingChars(ConcentriqExtension.concentriqUrlProperty().getValue(), '/') + "/" + IAS_MARKUPS_BULK_URL))
                .header("Authorization", "Bearer " + iasToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        logger.info(request.toString());

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                logger.error("Failed to create markups for layer {}: {} {}", layerId, response.statusCode(), response.body());
                return false;
            }
            return true;
        } catch (IOException e) {
            logger.error("Failed to create markups for layer {}", layerId, e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while creating markups for layer {}", layerId, e);
            return false;
        }
    }

    /**
     * One entry in the request body for {@link #createConcentriqMarkups(int, List, String)}:
     * {@code {"layerId": ..., "index": ..., "shape": {...}, "userDefinedFields": {}}}.
     */
    static class MarkupRequestBody {
        int layerId;
        int index;
        MarkupShape shape;
        Map<String, Object> userDefinedFields;

        MarkupRequestBody(int layerId, int index, MarkupShape shape, Map<String, Object> userDefinedFields) {
            this.layerId = layerId;
            this.index = index;
            this.shape = shape;
            this.userDefinedFields = userDefinedFields;
        }
    }

    static class MarkupShape {
        List<MarkupShapePoint> points;
        String type;

        MarkupShape(List<MarkupShapePoint> points, String type) {
            this.points = points;
            this.type = type;
        }
    }

    static class MarkupShapePoint {
        double x;
        double y;

        MarkupShapePoint(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Minimal shape of the API's JSON response, just enough for Gson to map the fields this
     * controller actually needs (pagination info and the image set rows).
     */
    static class ApiResponse {
        Meta meta;
        ResponseData data;
    }
 
    static class Meta {
        Pagination pagination;
    }
 
    static class Pagination {
        int page;
        int rowsPerPage;
        int totalRows;
    }
 
    static class ResponseData {
        List<ImageSet> imageSets;
    }
 
    static class ImageSet {
        int id;
        String name;
        String description;
        String thumbnailURL;
        String groupName;
        Integer groupId;
    }

    
    /**
     * Shape of the images API's JSON response, just enough for Gson to map the fields this
     * controller needs. Reuses {@link Meta} and {@link Pagination} above, since the pagination
     * shape is identical to the imageSets endpoint's.
     */
    static class ImagesApiResponse {
        Meta meta;
        ImagesResponseData data;
    }
 
    static class ImagesResponseData {
        List<ImageRecord> images;
    }
 
    public static class ImageRecord {
        public int id;
        String name;
        String slideName;
        int imgWidth;
        int imgHeight;
        double mppx;
        double mppy;
        ThumbUrl thumbURL;
        double objectivePower;
        int status;
    }

    /** Minimal shape of the {@code GET /api/images/:imageId} response. */
    static class ImageDetailResponse {
        ImageDetailData data;
    }
 
    static class ImageDetailData {
        ImageDataWrapper imageData;
        List<ThumbUrl> associatedImages;
        String storageKey;
    }
 
    static class ImageDataWrapper {
        Map<String, ImageSourceEntry> imageSources;
        Map<String, Object> fluorescenceChannels;
    }
 
    static class ImageSourceEntry {
        String imageServerUrl;
    }


    static class ThumbUrl {
        String type;
        String signedURL;
    }

    /**
     * Shape of the {@code pagination} query parameter sent with each request (serialized to JSON,
     * then URL-encoded). Kept separate from {@link Pagination} above, which describes the
     * pagination info coming back in the response instead.
     */
    static class PaginationRequest {
        int page;
        int rowsPerPage;
        boolean descending;
        List<String> sortBy;
 
        PaginationRequest(int page, int rowsPerPage, boolean descending, List<String> sortBy) {
            this.page = page;
            this.rowsPerPage = rowsPerPage;
            this.descending = descending;
            this.sortBy = sortBy;
        }
    }

    /**
     * A table cell that renders its String value (a thumbnail URL) as an actual picture.
     * <p>
     * Uses JavaFX's built-in background image loading ({@code backgroundLoading = true} in the
     * {@link Image} constructor), so the network fetch for each thumbnail happens off the JavaFX
     * Application Thread automatically - no extra threading code needed here. A blank or missing
     * URL (e.g. {@code ""} in the sample data) just results in an empty cell rather than an error.
     */
    static class ThumbnailCell extends TableCell<ImageTableRow, String> {
        private final ImageView imageView = new ImageView();
 
        ThumbnailCell() {
            imageView.setFitWidth(THUMBNAIL_SIZE);
            imageView.setFitHeight(THUMBNAIL_SIZE);
            imageView.setPreserveRatio(true);
        }
 
        @Override
        protected void updateItem(String url, boolean empty) {
            super.updateItem(url, empty);
            if (empty || url == null || url.isBlank()) {
                setGraphic(null);
            } else {
                imageView.setImage(new Image(url, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true, true, true));
                setGraphic(imageView);
            }
        }
    }


    /**
     * A {@link ImageServerBuilder.ServerBuilder} for images backed by Concentriq's IIIF tile
     * server. {@link #getMetadata()} returns cheap, single-level metadata known at import time
     * (no network access), while {@link #build()} does the real work: fetches an auth token and
     * the image's IIIF {@code info.json}, then constructs a full {@link ConcentriqTileServer}
     * capable of actually reading pixels.
     */
    public static class ConcentriqServerBuilder implements ImageServerBuilder.ServerBuilder<BufferedImage> {
        private final ImageServerMetadata metadata;
        private final int imageId;
        private final String name;
        private final double mppx;
        private final double mppy;
        private final double objectivePower;
 
        ConcentriqServerBuilder(ImageServerMetadata metadata, int imageId, String name, double mppx, double mppy, double objectivePower) {
            this.metadata = metadata;
            this.imageId = imageId;
            this.name = name;
            this.mppx = mppx;
            this.mppy = mppy;
            this.objectivePower = objectivePower;
        }

        @Override
        public ImageServer<BufferedImage> build() throws IOException {
            String token = fetchIasToken(imageId);
            if (token == null) {
                throw new IOException("Could not fetch IAS auth token for image " + imageId);
            }
 
            ImageDetails details = fetchImageDetails(imageId);
            if (details == null || details.imageServerUrl == null) {
                throw new IOException("Could not fetch imageServerUrl for image " + imageId);
            }
 
            return new ConcentriqTileServer(imageId, details.imageServerUrl, token, name, mppx, mppy, objectivePower, details.isFluorescence,
                    details.macroImageUrl, details.labelImageUrl);
        }
 
        @Override
        public Collection<URI> getURIs() {
            return Collections.emptyList();
        }
 
        @Override
        public ImageServerBuilder.ServerBuilder<BufferedImage> updateURIs(Map<URI, URI> updateMap) {
            return this; // no URIs are used, so there's nothing to update
        }
 
        @Override
        public Optional<ImageServerMetadata> getMetadata() {
            return Optional.of(metadata);
        }

        public int getImageId() {
            return imageId;
        }
    }

    /**
     * An {@link ImageServer} that reads pixels from Concentriq's IIIF tile server on demand.
     * <p>
     * Built once per opened image (via {@link ConcentriqServerBuilder#build()}), using the
     * image's {@code info.json} to declare a proper resolution pyramid so QuPath's tiling and
     * caching behave efficiently, then serving each requested tile as a plain IIIF region/size
     * request. IIIF's Level 1 {@code sizeByWh} support means arbitrary region+size combinations
     * can be requested directly - there's no need to snap requests onto the {@code scaleFactors}
     * advertised in {@code info.json}; those are only used here to pick sensible pyramid levels.
     */
    public static class ConcentriqTileServer extends AbstractTileableImageServer {
        private final int imageId;
        private final URI baseImageUri;
        private volatile String bearerToken;
        private final String name;
        private final double mppx;
        private final double mppy;
        private final boolean isFluorescence;
        private final ImageServerMetadata originalMetadata;
        private final double objectivePower;
        private final String macroImageUrl;
        private final String labelImageUrl;

 
        ConcentriqTileServer(int imageId, String imageServerUrl, String bearerToken, String name,
                             double mppx, double mppy, double objectivePower, boolean isFluorescence,
                             String macroImageUrl, String labelImageUrl) throws IOException {
            this.imageId = imageId;
            this.bearerToken = bearerToken;
            this.name = name;
            this.mppx = mppx;
            this.mppy = mppy;
            this.isFluorescence = isFluorescence;
            this.objectivePower = objectivePower;
            this.macroImageUrl = macroImageUrl;
            this.labelImageUrl = labelImageUrl;
 
            String base = imageServerUrl.endsWith("/info.json")
                    ? imageServerUrl.substring(0, imageServerUrl.length() - "/info.json".length())
                    : imageServerUrl;
            this.baseImageUri = URI.create(base);
 
            InfoJsonResponse info = fetchInfoJson(imageServerUrl, bearerToken);
            if (info == null) {
                throw new IOException("Could not read IIIF info.json for image " + imageId);
            }
 
            int tileWidth = 256;
            int tileHeight = 256;
            double[] downsamples = {1};
            if (info.tiles != null && !info.tiles.isEmpty()) {
                InfoJsonTile tile = info.tiles.get(0);
                tileWidth = tile.width;
                tileHeight = tile.height;
                if (tile.scaleFactors != null && !tile.scaleFactors.isEmpty()) {
                    downsamples = new double[tile.scaleFactors.size()];
                    for (int i = 0; i < downsamples.length; i++) {
                        downsamples[i] = tile.scaleFactors.get(i);
                    }
                }
            }
 
            // Fluorescence images are not RGB - they're one or more grayscale channel planes.
            // NOTE: tile fetching below still requests/decodes a single default.jpg per tile,
            // which is only correct for the RGB/brightfield case. Declaring rgb(false) here means
            // the metadata is honest about what kind of image this is, but doesn't by itself fix
            // how pixels are actually read for fluorescence - see readTile().
            var metadataBuilder = new ImageServerMetadata.Builder(ConcentriqTileServer.class, base, info.width, info.height)
                    .name(name)
                    .preferredTileSize(tileWidth, tileHeight)
                    .levelsFromDownsamples(downsamples)
                    .pixelType(PixelType.UINT8)
                    .rgb(/*!isFluorescence*/true);
 
            if (Double.isFinite(mppx) && Double.isFinite(mppy) && mppx > 0 && mppy > 0) {
                metadataBuilder = metadataBuilder.pixelSizeMicrons(mppx, mppy);
            }
            if (Double.isFinite(objectivePower) && objectivePower > 0) {
                metadataBuilder = metadataBuilder.magnification(objectivePower);
            }
 
            this.originalMetadata = metadataBuilder.build();
        }


        /**
         * The only names ever returned here are {@code "macro"}/{@code "label"} - whichever of
         * {@link #macroImageUrl}/{@link #labelImageUrl} are actually non-null for this image, not
         * a fixed pair every image is assumed to have.
         */
        @Override
        public List<String> getAssociatedImageList() {
            List<String> names = new ArrayList<>();
            if (macroImageUrl != null) {
                names.add("macro");
            }
            if (labelImageUrl != null) {
                names.add("label");
            }
            return names;
        }

        /**
         * Fetches the associated image lazily, on demand - the signed URL itself was already
         * captured at construction time (see {@link ConcentriqExtensionController#fetchImageDetails}),
         * but the actual image bytes are only downloaded here, the first time this is actually
         * called, not eagerly when the image is opened.
         */
        @Override
        public BufferedImage getAssociatedImage(String name) {
            String url;
            if ("macro".equals(name)) {
                url = macroImageUrl;
            } else if ("label".equals(name)) {
                url = labelImageUrl;
            } else {
                throw new IllegalArgumentException("Unknown associated image name: " + name);
            }
            if (url == null) {
                throw new IllegalArgumentException("No '" + name + "' associated image available for this image");
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            try {
                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() / 100 != 2) {
                    throw new IOException("Failed to fetch '" + name + "' associated image: " + response.statusCode());
                }
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));
                if (image == null) {
                    throw new IOException("Could not decode '" + name + "' associated image");
                }
                return image;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while fetching '" + name + "' associated image", e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to fetch '" + name + "' associated image", e);
            }
        }


        /**
         * Get the Concentriq image id this server was built from - used by external code (e.g.
         * a listener on {@code QuPathGUI.imageDataProperty()}) to identify that an opened image
         * came from Concentriq, and which one, so its annotations can eventually be fetched.
         * @return the Concentriq image id
         */
        public int getImageId() {
            return imageId;
        }
 
        @Override
        public ImageServerMetadata getOriginalMetadata() {
            return originalMetadata;
        }

        @Override
        public String getServerType() {
            return "Concentriq IIIF";
        }
 
        @Override
        public Collection<URI> getURIs() {
            return Collections.singletonList(baseImageUri);
        }
 
        @Override
        protected String createID() {
            return "concentriq:" + imageId;
        }
 
        @Override
        protected ImageServerBuilder.ServerBuilder<BufferedImage> createServerBuilder() {
            return new ConcentriqServerBuilder(originalMetadata, imageId, name, mppx, mppy, objectivePower);
        }
 
        @Override
        protected BufferedImage readTile(TileRequest tileRequest) throws IOException {
            RegionRequest region = tileRequest.getRegionRequest();
 
            String iiifRegion = region.getX() + "," + region.getY() + "," + region.getWidth() + "," + region.getHeight();
            String iiifSize = tileRequest.getTileWidth() + "," + tileRequest.getTileHeight();
            String url = baseImageUri + "/" + iiifRegion + "/" + iiifSize + "/0/default.jpg";
 
            HttpResponse<byte[]> response = sendTileRequest(url);

            if (response.statusCode() == 401) {
                // The token may have expired (it's issued with a limited lifetime) by the time
                // a tile is actually requested - e.g. panning/zooming well after the image was
                // first opened.  Concurrent tile reads may each independently trigger their own refetch here.
                logger.warn("Tile request returned 401 - refetching IAS token and retrying once for image {}", imageId);
                String freshToken = fetchIasToken(imageId);
                if (freshToken == null) {
                    throw new IOException("Tile request for " + url + " returned 401, and could not fetch a fresh IAS token to retry with");
                }
                bearerToken = freshToken;
                response = sendTileRequest(url);
            }

            if (response.statusCode() / 100 != 2) {
                throw new IOException("Tile request for " + url + " failed: " + response.statusCode());
            }
 
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(response.body()));
            if (img == null) {
                throw new IOException("Could not decode tile image from " + url);
            }
            return img;
        }


        private HttpResponse<byte[]> sendTileRequest(String url) throws IOException {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + bearerToken)
                    .GET()
                    .build();

            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while requesting tile at " + url, e);
            }
        }
    }


    /**
     * Fetch a IIIF {@code info.json} document for an image, authenticating with the same bearer
     * token used for tile requests.
     * @param imageServerUrl the image's {@code info.json} URL
     * @param bearerToken the token to authenticate with
     * @return the parsed response, or null if the request failed
     */
    private static InfoJsonResponse fetchInfoJson(String imageServerUrl, String bearerToken) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(imageServerUrl))
                .header("Authorization", "Bearer " + bearerToken)
                .GET()
                .build();
 
        try {
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            logger.info("IIIF info.json response from {}: {}", imageServerUrl, httpResponse.body());
            return gson.fromJson(httpResponse.body(), InfoJsonResponse.class);
        } catch (IOException e) {
            logger.error("Failed to fetch IIIF info.json from {}", imageServerUrl, e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while fetching IIIF info.json from {}", imageServerUrl, e);
            return null;
        }
    }
 
    /** Minimal shape of an IIIF {@code info.json} response - just the fields used here. */
    static class InfoJsonResponse {
        int width;
        int height;
        List<InfoJsonTile> tiles;
    }
 
    static class InfoJsonTile {
        int width;
        int height;
        List<Integer> scaleFactors;
    }


    /**
     * Parse a hex color string (e.g. {@code "#00FF2A"}) into a packed RGB integer suitable for
     * {@link PathClass#getInstance(String, Integer)}.
     * @param hex the hex color string, with or without a leading '#'
     * @return the packed RGB value, or null if the string was missing or unparseable
     */
    static Integer parseHexColor(String hex) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        String cleaned = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            int r = Integer.parseInt(cleaned.substring(0, 2), 16);
            int g = Integer.parseInt(cleaned.substring(2, 4), 16);
            int b = Integer.parseInt(cleaned.substring(4, 6), 16);
            return ColorTools.packRGB(r, g, b);
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            logger.warn("Could not parse annotation class color '{}'", hex, e);
            return null;
        }
    }


    /** Shape of the {@code GET /api/v3/annotationClasses} response. */
    static class AnnotationClassesResponse {
        List<AnnotationClassEntry> items;
    }
 
    static class AnnotationClassEntry {
        int id;
        String name;
        String color;
        boolean isArchived;
    }

    public static class AnnotationRecord {
        int id;
        String shape;
        String shapeString;
        String color;
        String annotationClassId;
        String annotationClassName;
        boolean isNegative;
        int imageId;
        String text;
    }

    /** Shape of the {@code GET /api/annotations} response. */
    static class AnnotationsApiResponse {
        AnnotationsResponseData data;
    }
 
    static class AnnotationsResponseData {
        List<AnnotationRecord> annotations;
    }


    /**
     * Build a "free"-format shapeString from just the first ("0th") coordinate set of a
     * (possibly multi-part, or polygon-with-holes) geometry - used when
     * {@link #computeUpdatedGeometryRoiShapeString} produces a result that isn't a simple single
     * ring, rather than {@link ROI#getAllPoints()}'s flattened
     * result (which would connect unrelated rings/parts together into one nonsensical shape).
     * <p>
     * {@code geometry.getGeometryN(0)} handles both cases uniformly: for a simple
     * {@code Polygon}, it returns the geometry itself; for a {@code MultiPolygon} or other
     * collection, it returns the first sub-geometry. If that first part is itself a
     * {@code Polygon}, only its exterior ring is used (its own interior holes, if any, are
     * dropped) - otherwise, its coordinates are used directly.
     * @param geometry the (possibly multi-part) result geometry
     * @param imageWidth the image's width in pixels
     * @return the resulting shapeString, using only the first ring/part's coordinates
     */
    private static String firstRingToShapeString(Geometry geometry, int imageWidth) {
        Geometry firstPart = geometry.getGeometryN(0);
        Coordinate[] coordinates = firstPart instanceof Polygon polygon
                ? polygon.getExteriorRing().getCoordinates()
                : firstPart.getCoordinates();
        return coordinatesToShapeString(coordinates, imageWidth);
    }


    /**
     * Convert a raw array of coordinates into a "free" (polygon/line) shapeString, scaling each
     * coordinate to the normalized 0-10000 range - extracted from {@link #firstRingToShapeString}
     * so the same conversion can be reused for other coordinate sources (e.g. an individual
     * hole's own interior ring, in {@link #extractHoleShapeStrings}).
     * @param coordinates the raw, full-resolution pixel coordinates
     * @param imageWidth the image's width in pixels
     * @return the resulting shapeString
     */
    private static String coordinatesToShapeString(Coordinate[] coordinates, int imageWidth) {
        StringBuilder shapeString = new StringBuilder();
        for (int i = 0; i < coordinates.length; i++) {
            double rawX = (coordinates[i].x * 10000) / imageWidth;
            double rawY = (coordinates[i].y * 10000) / imageWidth;
            if (i > 0) {
                shapeString.append(" ");
            }
            shapeString.append(rawX).append(",").append(rawY);
        }
        return shapeString.toString();
    }


    /**
     * Extract each interior ring (hole) from the edited, CURRENT GeometryROI's own geometry, as
     * its own shapeString - one per hole, in whatever order JTS happens to return them.
     * <p>
     * No attempt is made to match a specific hole to a specific original negative region's own
     * Concentriq id, a purely positional assignment across the full
     * set of associated negative-region ids is used as long as the counts match.
     * <p>
     * Holes are extracted from the CURRENT, edited geometry directly - not from
     * {@link #computeUpdatedGeometryRoiShapeString}'s own {@code newParentShapeString} result,
     * which has each hole RESTORED to its ORIGINAL boundary rather than its current one, and
     * would defeat the purpose entirely if used here instead.
     * <p>
     * If the edited geometry isn't a simple {@code Polygon} at all (e.g. it's a
     * {@code MultiPolygon} of disconnected islands), an empty list is returned.
     * @param editedParentRoi the dirty GeometryROI (parent) as currently edited in QuPath
     * @param imageWidth the image's width in pixels
     * @return one shapeString per hole currently present, in no particular/meaningful order, or
     *         an empty list if there are no holes (or the geometry isn't a simple Polygon)
     */
    public static List<String> extractHoleShapeStrings(ROI editedParentRoi, int imageWidth) {
        Geometry editedGeometry = GeometryTools.roiToGeometry(editedParentRoi);
        if (!(editedGeometry instanceof Polygon polygon)) {
            return List.of();
        }

        List<String> holeShapeStrings = new ArrayList<>();
        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
            holeShapeStrings.add(coordinatesToShapeString(polygon.getInteriorRingN(i).getCoordinates(), imageWidth));
        }
        return holeShapeStrings;
    }


    public static boolean hasDisconnectedPolygonIslands(ROI roi) {
        Geometry geometry = GeometryTools.roiToGeometry(roi);
        return geometry.getNumGeometries() > 1 && geometry.getGeometryN(0) instanceof Polygon;
    }


    /**
     * Sanitize a geometry so it's safe to pass to a binary overlay operation
     * ({@code .union(Geometry)}, {@code .intersection(Geometry)}, {@code .difference(Geometry)})
     * afterwards.
     * <p>
     * A real JTS limitation, confirmed by a real error thrown from a {@code union(Geometry)}
     * call: per JTS's own documentation, {@code "GeometryCollections are not supported as
     * arguments to the set-theoretic methods"} - specifically a genuinely mixed-type
     * {@link GeometryCollection}, not one of its more specific subtypes ({@code MultiPolygon},
     * {@code MultiPoint}, {@code MultiLineString}), which those methods handle fine.
     * {@code geometry.getClass().equals(GeometryCollection.class)} is the correct way to detect
     * specifically the generic case (not just {@code instanceof}, which would also match those
     * subtypes) - confirmed against real, production JTS-adjacent code using this exact check
     * for the same reason.
     * <p>
     * For a genuine mixed-type collection, this extracts and combines only its polygonal (i.e.
     * actually area-contributing) parts, discarding any {@code Point}/{@code LineString} parts.
     * An earlier version of this method instead tried dissolving via the collection's own
     * no-argument {@code Geometry.union()} - but that only works when the collection is
     * homogeneous once combined; confirmed by a real, logged case where {@code union()} was
     * called on a genuinely heterogeneous result (e.g. an intersection that produced both real
     * overlapping area <i>and</i> a degenerate touching point/line artifact from two boundaries
     * merely touching) and still returned a {@code GeometryCollection} - there's no single
     * geometry type that can represent both an area and a lower-dimensional artifact at once.
     * Since only the area matters for this project's use of these operations (restoring hole
     * area onto an edited shape), a zero-area point/line artifact is safe to discard rather than
     * needing to be represented at all.
     * @param geometry the geometry to sanitize
     * @return the geometry, reduced to just its polygonal parts if it was a mixed-type
     *         GeometryCollection, otherwise unchanged
     */
    private static Geometry dissolveIfCollection(Geometry geometry) {
        if (!geometry.getClass().equals(GeometryCollection.class)) {
            return geometry;
        }

        List<Polygon> polygons = new ArrayList<>();
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            Geometry part = geometry.getGeometryN(i);
            if (part instanceof Polygon polygon) {
                polygons.add(polygon);
            } else if (part instanceof MultiPolygon multiPolygon) {
                for (int j = 0; j < multiPolygon.getNumGeometries(); j++) {
                    polygons.add((Polygon) multiPolygon.getGeometryN(j));
                }
            }
            // Point/LineString parts contribute zero area - silently dropped.
        }

        if (polygons.isEmpty()) {
            return geometry.getFactory().createPolygon();
        }
        Geometry combined = polygons.get(0);
        for (int i = 1; i < polygons.size(); i++) {
            combined = combined.union(polygons.get(i));
        }
        return combined;
    }


    /**
     * The ROI class a given Concentriq {@code shape} value is expected to produce, per
     * {@link #toPathObjects}'s own shape-parsing branches - used on export to detect whether a
     * local edit has since changed an annotation's ROI to a type inconsistent with what it
     * originally was.
     * <p>
     * {@code "arrow"} and {@code "ruler"} both map to {@link LineROI} - they're geometrically
     * identical, distinguished only by metadata, not by ROI type (see
     * {@link #parseArrowRoi}/{@link #parseRulerRoi}).
     * @param concentriqShape the original {@code shape} value (e.g. from the
     *                        {@code concentriq_shape} metadata tag {@link #toPathObjects} sets)
     * @return the expected ROI class, or null if the shape value isn't recognized
     */
    public static Class<? extends ROI> expectedRoiClassForConcentriqShape(String concentriqShape) {
        if (concentriqShape == null) {
            return null;
        }
        return switch (concentriqShape) {
            case "rect" -> RectangleROI.class;
            case "ellipse" -> EllipseROI.class;
            case "free" -> PolygonROI.class;
            case "pin" -> PointsROI.class;
            case "arrow", "ruler" -> LineROI.class;
            default -> null;
        };
    }


    /**
     * Convert fetched Concentriq annotations into QuPath {@link PathObject}s.
     * <p>
     * Handles {@code shape} values {@code "rect"}, {@code "ellipse"}, {@code "free"} (polygon),
     * {@code "pin"} (point), {@code "arrow"}, and {@code "ruler"} - see {@link #parseRectRoi},
     * {@link #parseEllipseRoi}, {@link #parseFreeRoi}, {@link #parsePinRoi},
     * {@link #parseArrowRoi}, and {@link #parseRulerRoi} respectively for the per-shape parsing
     * details. Other shape values, or a {@code shapeString} that fails to parse, are skipped
     * with a warning rather than guessed at.
     * @param annotations the annotations to convert
     * @param metadata the opened image's metadata, used to convert normalized shapeString
     *                 coordinates into pixel coordinates
     * @return the resulting PathObjects (may be shorter than the input if some were skipped)
     */
    public static List<PathObject> toPathObjects(List<AnnotationRecord> annotations, ImageServerMetadata metadata) {
        List<PathObject> pathObjects = new ArrayList<>();
        List<PathObject> negativeRegions = new ArrayList<>();
        List<PathObject> potentialParents = new ArrayList<>();
        // Tracked separately from potentialParents - a rect/ellipse is never actually subtracted
        // from (changing its shape type isn't supported in Concentriq), but is still checked for
        // overlap, purely to show a specific warning rather than the generic "unmatched" one.
        List<PathObject> rectOrEllipseParents = new ArrayList<>();
        Map<PathObject, Integer> negativeRegionIds = new HashMap<>();

        int imageHeight = metadata.getHeight();
        int imageWidth = metadata.getWidth();
        double aspectRatio = imageHeight / (float) imageWidth;

        for (AnnotationRecord annotation : annotations) {
            ROI roi;
            if ("rect".equals(annotation.shape)) {
                roi = parseRectRoi(annotation, imageWidth, imageHeight, aspectRatio);
            } else if ("ellipse".equals(annotation.shape)) {
                roi = parseEllipseRoi(annotation, imageWidth, imageHeight, aspectRatio);
            } else if ("free".equals(annotation.shape)) {
                roi = parseFreeRoi(annotation, imageWidth, imageHeight, aspectRatio);
            } else if ("pin".equals(annotation.shape)) {
                roi = parsePinRoi(annotation, imageWidth, imageHeight, aspectRatio);
            } else if ("arrow".equals(annotation.shape)) {
                roi = parseArrowRoi(annotation, imageWidth, imageHeight, aspectRatio);
            } else if ("ruler".equals(annotation.shape)) {
                roi = parseRulerRoi(annotation, imageWidth, imageHeight, aspectRatio);
            } else {
                logger.warn("Skipping annotation {} - unsupported shape '{}'", annotation.id, annotation.shape);
                continue;
            }

            if (roi == null) {
                continue; // parsing failed - already logged inside the parse method
            }

            PathClass pathClass = (annotation.annotationClassName != null && !annotation.annotationClassName.isBlank())
                    ? PathClass.getInstance(annotation.annotationClassName)
                    : null;

            PathObject pathObject = PathObjects.createAnnotationObject(roi, pathClass);

            if ("arrow".equals(annotation.shape)) {
                // Concentriq doesn't give us a direction to key off - always place the
                // arrowhead at the line's end, matching QuPath's ARROW_END tool mode. Stored via
                // PathObject's generic metadata map, the same mechanism QuPath's own arrow tool
                // uses, confirmed via the "arrowhead" key holding '<'/'>' seen in QuPath itself.
                pathObject.getMetadata().put("arrowhead", ">");
            } else if ("ruler".equals(annotation.shape)) {
                // "ruler" and "arrow" both produce a plain LineROI with nothing else to tell
                // them apart afterwards - this tag preserves which Concentriq shape the line
                // actually came from.
                pathObject.getMetadata().put("associated_concentriq_shape", "ruler");
            }

            pathObject.getMetadata().put("concentriq_annotation_id", String.valueOf(annotation.id));

            // Tracks the shape this annotation originally had when pulled from Concentriq
            // (independent of the arrow/ruler-specific tags above) - used on export to detect
            // whether a local edit (e.g. brush/wand tool) has changed its ROI to a type
            // inconsistent with this original shape, before attempting to write anything back.
            pathObject.getMetadata().put("concentriq_shape", annotation.shape);

            Integer color = parseHexColor(annotation.color);
            if (color != null) {
                pathObject.setColor(color);
            }
            if (annotation.text != null && !annotation.text.isBlank()) {
                pathObject.setName(annotation.text);
            }

            if (annotation.isNegative) {
                negativeRegions.add(pathObject);
                negativeRegionIds.put(pathObject, annotation.id);
            } else {
                pathObjects.add(pathObject);
                if (roi instanceof PolygonROI) {
                    potentialParents.add(pathObject);
                } else if (roi instanceof RectangleROI || roi instanceof EllipseROI) {
                    rectOrEllipseParents.add(pathObject);
                }
            }
        }

        Map<PathObject, List<Integer>> containedNegativeRegionIds = new HashMap<>();

        for (PathObject negativeRegion : negativeRegions) {
            var negativeGeometry = GeometryTools.roiToGeometry(negativeRegion.getROI());
            double negativeRegionArea = negativeGeometry.getArea();
            int negativeRegionId = negativeRegionIds.get(negativeRegion);

            boolean explainedOmission = false;

            List<PathObject> sufficientlyOverlappingParents = new ArrayList<>();
            for (PathObject parent : potentialParents) {
                if (!Objects.equals(negativeRegion.getPathClass(), parent.getPathClass())) {
                    continue;
                }

                var parentGeometry = GeometryTools.roiToGeometry(parent.getROI());
                if (!parentGeometry.intersects(negativeGeometry)) {
                    continue;
                }

                double overlapFraction = parentGeometry.intersection(negativeGeometry).getArea() / negativeRegionArea;
                if (overlapFraction < MINIMUM_NEGATIVE_REGION_OVERLAP_FRACTION) {
                    logger.warn("Negative region annotation {} overlaps positive region {} by only {}% of its own area " +
                            "(below the {}% threshold) - skipping", negativeRegionId, parent.getMetadata().get("concentriq_annotation_id"),
                            Math.round(overlapFraction * 100), Math.round(MINIMUM_NEGATIVE_REGION_OVERLAP_FRACTION * 100));
                    Dialogs.showWarningNotification("Warning",
                            "The semantics of exclusion with ID " + negativeRegionId + "\n are unclear, skipping that annotation. "
                                    + "\nMake it more precise and refresh.");
                    explainedOmission = true;
                    continue;
                }

                sufficientlyOverlappingParents.add(parent);
            }

            if (sufficientlyOverlappingParents.size() == 1) {
                PathObject parent = sufficientlyOverlappingParents.get(0);
                var parentGeometry = GeometryTools.roiToGeometry(parent.getROI());

                Geometry geometryToSubtract;
                if (parentGeometry.contains(negativeGeometry)) {
                    geometryToSubtract = negativeGeometry;
                } else {
                    Geometry clipped = negativeGeometry.intersection(parentGeometry);
                    Geometry shrunk = clipped.buffer(-NEGATIVE_REGION_CLAMP_MARGIN_PIXELS);

                    boolean shrinkFailed = shrunk.isEmpty() || shrunk.getNumGeometries() > clipped.getNumGeometries();
                    if (shrinkFailed) {
                        logger.warn("Negative region annotation {} could not be clamped inside positive region {} - shrinking " +
                                        "the clipped overlap by {} pixel(s) emptied or split it - skipping",
                                negativeRegionId, parent.getMetadata().get("concentriq_annotation_id"), NEGATIVE_REGION_CLAMP_MARGIN_PIXELS);
                        Dialogs.showWarningNotification("Warning",
                                "The semantics of exclusion with ID " + negativeRegionId + " \nare unclear, unable to clamp because doing so\n"
                                        + "would split or remove the region entirely. \nMake it more precise and refresh.");
                        explainedOmission = true;
                        geometryToSubtract = null;
                    } else {
                        geometryToSubtract = shrunk;
                        parent.getMetadata().put("synced", "false");
                    }
                }

                if (geometryToSubtract != null) {
                    var resultGeometry = GeometryTools.homogenizeGeometryCollection(parentGeometry.difference(geometryToSubtract));
                    ROI newParentRoi = GeometryTools.geometryToROI(resultGeometry, ImagePlane.getDefaultPlane());
                    if (parent instanceof PathAnnotationObject parentAnnotationObject) {
                        parentAnnotationObject.setROI(newParentRoi);
                    }
                    containedNegativeRegionIds.computeIfAbsent(parent, key -> new ArrayList<>()).add(negativeRegionId);
                    explainedOmission = true;
                }
            } else if (sufficientlyOverlappingParents.size() > 1) {
                logger.warn("Negative region annotation {} sufficiently overlaps {} distinct positive regions - " +
                        "ambiguous, not clamping or subtracting", negativeRegionId, sufficientlyOverlappingParents.size());
                Dialogs.showWarningNotification("Warning",
                        "The semantics of exclusion with ID " + negativeRegionId + " are\n unclear, unable to clamp due to multiple associated positive ROI's");
                explainedOmission = true;
            }

            for (PathObject rectOrEllipseParent : rectOrEllipseParents) {
                if (!Objects.equals(negativeRegion.getPathClass(), rectOrEllipseParent.getPathClass())) {
                    continue;
                }
                var rectOrEllipseGeometry = GeometryTools.roiToGeometry(rectOrEllipseParent.getROI());
                if (!rectOrEllipseGeometry.intersects(negativeGeometry)) {
                    continue;
                }

                String shapeWord = rectOrEllipseParent.getROI() instanceof RectangleROI ? "rectangle" : "ellipse";
                logger.warn("Negative region annotation {} overlaps a same-classification {} ({}) - changing its shape type " +
                        "is not supported in Concentriq, so it was not imported", negativeRegionId, shapeWord,
                        rectOrEllipseParent.getMetadata().get("concentriq_annotation_id"));
                Dialogs.showWarningNotification("Warning",
                        "Excluded region with id " + negativeRegionId + " could not be imported\n because it would require changing a "
                                + shapeWord + " to\n a polygon, which is currently unsupported in\n Concentriq. "
                                + "Change the positive region to a \npolygon in Concentriq and refresh.");
                explainedOmission = true;
            }

            if (!explainedOmission) {
                // Classified as QuPath's own Ignore class so it's visually/semantically marked
                // as not meaningful on its own, but its original Concentriq classification
                // is preserved as metadata so exports can send that original classification back rather than "Ignore*" itself.
                PathClass originalPathClass = negativeRegion.getPathClass();
                negativeRegion.getMetadata().put("concentriq_original_annotation_class",
                        originalPathClass != null ? originalPathClass.getName() : "");
                negativeRegion.setPathClass(PathClass.StandardPathClasses.IGNORE);
                pathObjects.add(negativeRegion);
                logger.info("Negative region annotation {} has no matching (same-classification, spatially-overlapping) " +
                        "positive region - imported as a standalone, Ignore-classified annotation instead", negativeRegionId);
            }
        }

        for (Map.Entry<PathObject, List<Integer>> entry : containedNegativeRegionIds.entrySet()) {
            entry.getKey().getMetadata().put("contained_negative_concentriq_regions", gson.toJson(entry.getValue()));
        }

        return pathObjects;
    }

    
    /**
     * Parse a fixed 4-value {@code x y width height} shapeString - shared by both {@code "rect"}
     * and {@code "ellipse"}, which use an identical shapeString format (an ellipse is defined by
     * its bounding box) and differ only in which {@link ROIs} factory method is ultimately
     * called. Confirmed for ellipses by cross-checking a real annotation's reported {@code size}
     * against {@code π × (width/2) × (height/2)} computed from this same scaling.
     * @return the parsed {@code [x, y, width, height]} in pixel coordinates, or null if
     *         shapeString was missing or unparseable
     */
    private static double[] parseBoxCoordinates(AnnotationRecord annotation, int imageWidth, int imageHeight, double aspectRatio) {
        if (annotation.shapeString == null || annotation.shapeString.isBlank()) {
            logger.warn("Skipping annotation {} - no shapeString", annotation.id);
            return null;
        }

        String[] parts = annotation.shapeString.trim().split("\\s+");
        if (parts.length != 4) {
            logger.warn("Skipping annotation {} - expected 4 values in shapeString, got {}", annotation.id, parts.length);
            return null;
        }

        try {
            double x = (Double.parseDouble(parts[0]) * imageWidth) / 10000;
            double y = (Double.parseDouble(parts[1]) * imageHeight) / (10000 * aspectRatio);
            double width = (((Double.parseDouble(parts[2]) + Double.parseDouble(parts[0])) * imageWidth) / 10000) - x;
            double height = (((Double.parseDouble(parts[3]) + Double.parseDouble(parts[1])) * imageHeight) / (10000 * aspectRatio)) - y;
            return new double[] {x, y, width, height};
        } catch (NumberFormatException e) {
            logger.warn("Skipping annotation {} - could not parse shapeString '{}'", annotation.id, annotation.shapeString, e);
            return null;
        }
    }

    /**
     * Parse a {@code "rect"} annotation's shapeString - four space-separated numbers in
     * {@code x y width height} order, on a normalized 0-10000 scale - into pixel coordinates.
     * @return the resulting ROI, or null if shapeString was missing or unparseable
     */
    private static ROI parseRectRoi(AnnotationRecord annotation, int imageWidth, int imageHeight, double aspectRatio) {
        double[] box = parseBoxCoordinates(annotation, imageWidth, imageHeight, aspectRatio);
        if (box == null) {
            return null;
        }
        return ROIs.createRectangleROI(box[0], box[1], box[2], box[3], ImagePlane.getDefaultPlane());
    }

    /**
     * Parse an {@code "ellipse"} annotation's shapeString - the same fixed 4-value
     * {@code x y width height} bounding-box format as {@code "rect"} (an ellipse is defined by
     * its bounding box, inscribed within it) - into pixel coordinates.
     * @return the resulting ROI, or null if shapeString was missing or unparseable
     */
    private static ROI parseEllipseRoi(AnnotationRecord annotation, int imageWidth, int imageHeight, double aspectRatio) {
        double[] box = parseBoxCoordinates(annotation, imageWidth, imageHeight, aspectRatio);
        if (box == null) {
            return null;
        }
        return ROIs.createEllipseROI(box[0], box[1], box[2], box[3], ImagePlane.getDefaultPlane());
    }

    /**
     * Parse a {@code "free"} (freehand/polygon) annotation's shapeString - a space-separated
     * list of comma-separated {@code "x,y"} vertex pairs, e.g. {@code "1.2,3.4 5.6,7.8 ..."} -
     * unlike {@code "rect"}'s fixed 4-value format. Each vertex is on the same normalized
     * 0-10000 scale as {@code "rect"}, converted to pixels the same way.
     * @return the resulting polygon ROI, or null if shapeString was missing, had fewer than 3
     *         points, or contained an unparseable point
     */
    private static ROI parseFreeRoi(AnnotationRecord annotation, int imageWidth, int imageHeight, double aspectRatio) {
        if (annotation.shapeString == null || annotation.shapeString.isBlank()) {
            logger.warn("Skipping annotation {} - no shapeString", annotation.id);
            return null;
        }

        String[] points = annotation.shapeString.trim().split("\\s+");
        if (points.length < 3) {
            logger.warn("Skipping annotation {} - expected at least 3 points for a 'free' shape, got {}",
                    annotation.id, points.length);
            return null;
        }

        double[] xs = new double[points.length];
        double[] ys = new double[points.length];
        try {
            for (int i = 0; i < points.length; i++) {
                String[] xy = points[i].split(",");
                if (xy.length != 2) {
                    logger.warn("Skipping annotation {} - malformed point '{}' in shapeString", annotation.id, points[i]);
                    return null;
                }
                double normX = Double.parseDouble(xy[0]);
                double normY = Double.parseDouble(xy[1]);
                xs[i] = (normX * imageWidth) / 10000;
                ys[i] = (normY * imageHeight) / (10000 * aspectRatio);
            }
        } catch (NumberFormatException e) {
            logger.warn("Skipping annotation {} - could not parse shapeString '{}'", annotation.id, annotation.shapeString, e);
            return null;
        }

        return ROIs.createPolygonROI(xs, ys, ImagePlane.getDefaultPlane());
    }

     /**
     * Parse a {@code "pin"} annotation's shapeString - just two space-separated numbers,
     * {@code x y}, on the same normalized 0-10000 scale as the other shapes - into a single-point
     * ROI. Confirmed by a real annotation whose {@code boundsString} was {@code "x y 0 0"} and
     * whose reported {@code size} was exactly {@code 0} - a point has no width, height, or area.
     * @return the resulting point ROI, or null if shapeString was missing or unparseable
     */
    private static ROI parsePinRoi(AnnotationRecord annotation, int imageWidth, int imageHeight, double aspectRatio) {
        if (annotation.shapeString == null || annotation.shapeString.isBlank()) {
            logger.warn("Skipping annotation {} - no shapeString", annotation.id);
            return null;
        }

        String[] parts = annotation.shapeString.trim().split("\\s+");
        if (parts.length != 2) {
            logger.warn("Skipping annotation {} - expected 2 values in shapeString, got {}", annotation.id, parts.length);
            return null;
        }

        try {
            double x = (Double.parseDouble(parts[0]) * imageWidth) / 10000;
            double y = (Double.parseDouble(parts[1]) * imageHeight) / (10000 * aspectRatio);
            return ROIs.createPointsROI(x, y, ImagePlane.getDefaultPlane());
        } catch (NumberFormatException e) {
            logger.warn("Skipping annotation {} - could not parse shapeString '{}'", annotation.id, annotation.shapeString, e);
            return null;
        }
    }

    /**
     * Parse a fixed 4-value {@code x y x2 y2} shapeString - shared by both {@code "arrow"} and
     * {@code "ruler"}, which use an identical shapeString format (an independent absolute second
     * endpoint, not a width/height offset) and differ only in what metadata gets attached to the
     * resulting object afterwards.
     * <p>
     * Confirmed against real "arrow" and "ruler" annotations two ways each: their
     * {@code boundsString} was derived ({@code width = x2 - x1}, {@code height = y2 - y1})
     * rather than identical to {@code shapeString} the way {@code "rect"}'s is - confirming
     * {@code x2}/{@code y2} are a genuine absolute second point, not a pre-computed width/height.
     * And their reported {@code size} matched this scaling's computed line length
     * ({@code √(dx² + dy²)}) almost exactly in both cases - confirming the same scaling
     * transform (including its width-based quirk) applies correctly here too.
     * @return the parsed {@code [x, y, x2, y2]} in pixel coordinates, or null if shapeString was
     *         missing or unparseable
     */
    private static double[] parseLineEndpoints(AnnotationRecord annotation, int imageWidth, int imageHeight, double aspectRatio) {
        if (annotation.shapeString == null || annotation.shapeString.isBlank()) {
            logger.warn("Skipping annotation {} - no shapeString", annotation.id);
            return null;
        }

        String[] parts = annotation.shapeString.trim().split("\\s+");
        if (parts.length != 4) {
            logger.warn("Skipping annotation {} - expected 4 values in shapeString, got {}", annotation.id, parts.length);
            return null;
        }

        try {
            double x = (Double.parseDouble(parts[0]) * imageWidth) / 10000;
            double y = (Double.parseDouble(parts[1]) * imageHeight) / (10000 * aspectRatio);
            double x2 = (Double.parseDouble(parts[2]) * imageWidth) / 10000;
            double y2 = (Double.parseDouble(parts[3]) * imageHeight) / (10000 * aspectRatio);
            return new double[] {x, y, x2, y2};
        } catch (NumberFormatException e) {
            logger.warn("Skipping annotation {} - could not parse shapeString '{}'", annotation.id, annotation.shapeString, e);
            return null;
        }
    }

    /**
     * Parse an {@code "arrow"} annotation's shapeString into a line ROI - see
     * {@link #parseLineEndpoints} for the shared parsing details.
     * @return the resulting line ROI, or null if shapeString was missing or unparseable
     */
    private static ROI parseArrowRoi(AnnotationRecord annotation, int imageWidth, int imageHeight, double aspectRatio) {
        double[] endpoints = parseLineEndpoints(annotation, imageWidth, imageHeight, aspectRatio);
        if (endpoints == null) {
            return null;
        }
        return ROIs.createLineROI(endpoints[0], endpoints[1], endpoints[2], endpoints[3], ImagePlane.getDefaultPlane());
    }

    /**
     * Parse a {@code "ruler"} annotation's shapeString into a line ROI - see
     * {@link #parseLineEndpoints} for the shared parsing details. Identical geometry to
     * {@code "arrow"}; the two are distinguished afterwards only by the metadata attached to
     * the resulting object.
     * @return the resulting line ROI, or null if shapeString was missing or unparseable
     */
    private static ROI parseRulerRoi(AnnotationRecord annotation, int imageWidth, int imageHeight, double aspectRatio) {
        double[] endpoints = parseLineEndpoints(annotation, imageWidth, imageHeight, aspectRatio);
        if (endpoints == null) {
            return null;
        }
        return ROIs.createLineROI(endpoints[0], endpoints[1], endpoints[2], endpoints[3], ImagePlane.getDefaultPlane());
    }


    /**
     * Convert GeoJSON into an XML annotation document.
     * This is the simpler, standalone {@code geojson_to_xml.py} script's version (no
     * {@code "MultiPolygon"}, {@code "MultiPoint"}, or {@code "MultiLineString"} support), and
     * classification name/color are used directly from the GeoJSON with no lookup against
     * Concentriq's own annotation classes.
     * @param geojsonData the parsed GeoJSON feature collection
     * @param ignore if true, every Annotation is named "Unnamed" with LineColor "0", regardless
     *               of the GeoJSON's own classification data
     * @param sourceAnnotations the original annotations the GeoJSON was exported from, in the
     *                          same order, used only to detect rectangles - pass null (or an
     *                          empty list) to skip rectangle detection entirely
     * @return the resulting XML document
     */
    public static Document geojsonToXml(GeoJsonFeatureCollection geojsonData, boolean ignore, List<PathObject> sourceAnnotations) {
        Document document;
        try {
            document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException e) {
            // Only fails if the JVM's XML parser itself is misconfigured - not a normal,
            // per-call failure mode, so this isn't propagated as a checked exception.
            throw new RuntimeException("Could not create a new XML Document", e);
        }

        Element annotationsElement = document.createElement("Annotations");
        document.appendChild(annotationsElement);

        List<GeoJsonFeature> features = geojsonData.features != null ? geojsonData.features : List.of();
        for (int featureIndex = 0; featureIndex < features.size(); featureIndex++) {
            GeoJsonFeature feature = features.get(featureIndex);
            GeoJsonGeometry geometry = feature.geometry;
            GeoJsonProperties properties = feature.properties;

            if (geometry == null) {
                continue;
            }

            // Region.Type is Concentriq's own numeric shape code, not the GeoJSON type name.
            String regionType;
            List<double[]> vertices;
            if ("Polygon".equals(geometry.type)) {
                ROI sourceRoi = (sourceAnnotations != null && featureIndex < sourceAnnotations.size())
                        ? sourceAnnotations.get(featureIndex).getROI()
                        : null;

                List<List<double[]>> allRings = extractAllPolygonRings(geometry.coordinates);

                if (sourceRoi instanceof GeometryROI && allRings.size() > 1) {
                    // A GeometryROI with more than one ring represents a polygon with one or
                    // more negative regions ("holes") cut out of it. Per the GeoJSON
                    // specification (the first ring is always the
                    // exterior (parent) boundary and any subsequent rings are interior holes.
                    // Written as two separate Annotation blocks.
                    List<double[]> parentVertices = allRings.get(0);
                    List<RegionSpec> negativeRegionSpecs = new ArrayList<>();
                    for (int ringIndex = 1; ringIndex < allRings.size(); ringIndex++) {
                        negativeRegionSpecs.add(new RegionSpec("0", allRings.get(ringIndex), true));
                    }

                    appendAnnotation(document, annotationsElement, properties, ignore,
                            List.of(new RegionSpec("0", parentVertices, false)));
                    appendAnnotation(document, annotationsElement, properties, ignore, negativeRegionSpecs);
                    continue;
                }

                List<double[]> exteriorRing = allRings.isEmpty() ? new ArrayList<>() : allRings.get(0);
                if (sourceRoi instanceof EllipseROI) {
                    // Concentriq represents an ellipse by its bounding box's two diagonal
                    // corners, not the curve - computed directly from the original ROI's bounds,
                    // not from the GeoJSON's polygon-approximated vertices, which trace the
                    // curve and would be the wrong shape entirely for this format.
                    regionType = "2";
                    vertices = List.of(
                            new double[] {sourceRoi.getBoundsX(), sourceRoi.getBoundsY()},
                            new double[] {sourceRoi.getBoundsX() + sourceRoi.getBoundsWidth(),
                                    sourceRoi.getBoundsY() + sourceRoi.getBoundsHeight()});
                } else if (sourceRoi instanceof RectangleROI) {
                    regionType = "1"; // rectangle - same vertices as a generic polygon, different Region.Type
                    vertices = exteriorRing;
                } else {
                    regionType = "0"; // "free" (polygon)
                    vertices = exteriorRing;
                }
            } else if ("Point".equals(geometry.type)) {
                regionType = "5"; // single point ("pin")
                vertices = extractPointVertex(geometry.coordinates);
            } else if ("LineString".equals(geometry.type)) {
                // Unlike rectangle/ellipse, GeoJSON has a distinct, proper geometry type for a
                // line - no need to inspect the original ROI to disambiguate it from anything
                // else. The vertices are the line's own endpoints as-is (confirmed against a
                // real sample: the second vertex is not derivable as the first plus any
                // width/height, ruling out a bounding-box-style interpretation like ellipse's).
                vertices = extractLineVertices(geometry.coordinates);

                // An "arrow" is a line whose metadata has "arrowhead" == ">" (set by
                // toPathObjects() on annotations read from Concentriq - see its javadoc; a
                // freshly-drawn local line never has this key at all). Confirmed with the user
                // that Concentriq only supports end-pointing arrows, matching this project's own
                // "always end-facing" convention exactly - so no vertex reordering is needed,
                // the line's existing [start, end] order already puts the endpoint last.
                boolean isArrow = sourceAnnotations != null && featureIndex < sourceAnnotations.size()
                        && ">".equals(sourceAnnotations.get(featureIndex).getMetadata().get("arrowhead"));
                regionType = isArrow ? "3" : "4"; // "3" = arrow; "4" = plain line

                if (isArrow && vertices.size() == 2) {
                    // Empirically confirmed (by testing a real export) that the arrowhead came
                    // out on the wrong side without this - QuPath's own line vertex order
                    // apparently does NOT already put the "pointy end" last, contrary to the
                    // original assumption. Reversing the two vertices fixes it.
                    vertices = List.of(vertices.get(1), vertices.get(0));
                }
            } else {
                continue; // unsupported geometry type - skipped silently, matching the Python original
            }

            appendAnnotation(document, annotationsElement, properties, ignore, List.of(new RegionSpec(regionType, vertices, false)));
        }

        return document;
    }

    /**
     * Convenience overload of {@link #geojsonToXml(GeoJsonFeatureCollection, boolean, List)}
     * that parses raw GeoJSON text first, matching how {@code geojson_to_xml.py}'s own
     * {@code main()} reads a file via {@code json.load(...)} before calling the function.
     * @param geojsonText raw GeoJSON text
     * @param ignore see {@link #geojsonToXml(GeoJsonFeatureCollection, boolean, List)}
     * @param sourceAnnotations see {@link #geojsonToXml(GeoJsonFeatureCollection, boolean, List)}
     * @return the resulting XML document
     */
    public static Document geojsonToXml(String geojsonText, boolean ignore, List<PathObject> sourceAnnotations) {
        GeoJsonFeatureCollection geojsonData = gson.fromJson(geojsonText, GeoJsonFeatureCollection.class);
        return geojsonToXml(geojsonData, ignore, sourceAnnotations);
    }


    /**
     * A single {@code <Region>} to be written within an {@code <Annotation>} - its Concentriq
     * {@code Type} code, its vertices, and whether it's a negative region ({@code NegativeROA}).
     */
    private record RegionSpec(String type, List<double[]> vertices, boolean negativeRoa) {}

    /**
     * Build one {@code <Annotation>} element (with the given classification-derived Name/Color)
     * containing one {@code <Region>} per {@link RegionSpec}, and append it to
     * {@code annotationsElement}. Shared by every shape's normal (single-region) case and by the
     * two-Annotation-block negative-region case, so the Name/Visible/LineColor/HasEndcaps/Vertex
     * conventions can't drift between them.
     */
    private static void appendAnnotation(Document document, Element annotationsElement, GeoJsonProperties properties,
                                          boolean ignore, List<RegionSpec> regionSpecs) {
        GeoJsonClassification classification = properties != null ? properties.classification : null;
        List<Integer> color = (classification != null && classification.color != null)
                ? classification.color
                : List.of(0, 0, 0);
        int lineColor = (color.get(2) << 16) | (color.get(1) << 8) | color.get(0); // [r,g,b] -> ColorRef (BGR)

        Element annotationElement = document.createElement("Annotation");
        if (ignore) {
            annotationElement.setAttribute("Name", "Unnamed");
            annotationElement.setAttribute("Visible", "True");
            annotationElement.setAttribute("LineColor", "0");
        } else {
            String name = (classification != null && classification.name != null)
                    ? classification.name
                    : "Unnamed";
            annotationElement.setAttribute("Name", name);
            annotationElement.setAttribute("Visible", "True");
            annotationElement.setAttribute("LineColor", String.valueOf(lineColor));
        }
        annotationsElement.appendChild(annotationElement);

        Element regionsElement = document.createElement("Regions");
        annotationElement.appendChild(regionsElement);

        for (RegionSpec regionSpec : regionSpecs) {
            Element regionElement = document.createElement("Region");
            regionElement.setAttribute("Type", regionSpec.type());
            regionElement.setAttribute("HasEndcaps", "0");
            regionElement.setAttribute("NegativeROA", regionSpec.negativeRoa() ? "1" : "0");
            regionsElement.appendChild(regionElement);

            Element verticesElement = document.createElement("Vertices");
            regionElement.appendChild(verticesElement);

            for (double[] vertex : regionSpec.vertices()) {
                Element vertexElement = document.createElement("Vertex");
                vertexElement.setAttribute("X", String.valueOf(vertex[0]));
                vertexElement.setAttribute("Y", String.valueOf(vertex[1]));
                vertexElement.setAttribute("Z", "0");
                verticesElement.appendChild(vertexElement);
            }
        }
    }


    /**
     * Extract a {@code "Polygon"} geometry's exterior ring vertices - the first of
     * {@link #extractAllPolygonRings}'s rings, or an empty list if there are none.
     * @param coordinates the geometry's raw {@code coordinates} JSON value
     * @return the exterior ring's vertices as {@code [x, y]} pairs, or an empty list if absent
     */
    private static List<double[]> extractPolygonVertices(JsonElement coordinates) {
        List<List<double[]>> rings = extractAllPolygonRings(coordinates);
        return rings.isEmpty() ? new ArrayList<>() : rings.get(0);
    }

    /**
     * Extract every ring of a {@code "Polygon"} geometry - {@code coordinates} is a list of
     * rings (each ring a list of {@code [x, y]} points). Per the GeoJSON specification, the
     * first ring is always the exterior boundary and any subsequent rings are interior holes -
     * relied on directly here (confirmed sufficient by the user) rather than independently
     * verified via spatial containment.
     * @param coordinates the geometry's raw {@code coordinates} JSON value
     * @return every ring's vertices as {@code [x, y]} pairs, or an empty list if absent
     */
    private static List<List<double[]>> extractAllPolygonRings(JsonElement coordinates) {
        List<List<double[]>> rings = new ArrayList<>();
        if (coordinates == null || !coordinates.isJsonArray()) {
            return rings;
        }
        JsonArray ringsJson = coordinates.getAsJsonArray();
        for (JsonElement ringElement : ringsJson) {
            JsonArray ringJson = ringElement.getAsJsonArray();
            List<double[]> ring = new ArrayList<>();
            for (JsonElement pointElement : ringJson) {
                JsonArray point = pointElement.getAsJsonArray();
                ring.add(new double[] {point.get(0).getAsDouble(), point.get(1).getAsDouble()});
            }
            rings.add(ring);
        }
        return rings;
    }

    /**
     * Extract a {@code "Point"} geometry's single vertex - {@code coordinates} here is a flat
     * {@code [x, y]} pair, not nested like a polygon's.
     * @param coordinates the geometry's raw {@code coordinates} JSON value
     * @return a single-element list containing the point's {@code [x, y]}, or an empty list if
     *         absent or malformed
     */
    private static List<double[]> extractPointVertex(JsonElement coordinates) {
        List<double[]> vertices = new ArrayList<>();
        if (coordinates == null || !coordinates.isJsonArray()) {
            return vertices;
        }
        JsonArray point = coordinates.getAsJsonArray();
        if (point.size() >= 2) {
            vertices.add(new double[] {point.get(0).getAsDouble(), point.get(1).getAsDouble()});
        }
        return vertices;
    }


    /**
     * Extract a {@code "LineString"} geometry's vertices - {@code coordinates} is a flat list of
     * {@code [x, y]} points (like {@code "Point"}'s, but potentially more than one - in practice
     * always exactly two for a QuPath {@link qupath.lib.roi.LineROI}), unlike a polygon's nested
     * rings.
     * @param coordinates the geometry's raw {@code coordinates} JSON value
     * @return the line's vertices as {@code [x, y]} pairs, or an empty list if absent
     */
    private static List<double[]> extractLineVertices(JsonElement coordinates) {
        List<double[]> vertices = new ArrayList<>();
        if (coordinates == null || !coordinates.isJsonArray()) {
            return vertices;
        }
        JsonArray points = coordinates.getAsJsonArray();
        for (JsonElement pointElement : points) {
            JsonArray point = pointElement.getAsJsonArray();
            if (point.size() >= 2) {
                vertices.add(new double[] {point.get(0).getAsDouble(), point.get(1).getAsDouble()});
            }
        }
        return vertices;
    }


    /**
     * Serialize an XML {@link Document} to a String - the equivalent of Python's
     * {@code ET.tostring(tree.getroot(), encoding='unicode')}.
     * @param document the document to serialize
     * @return the XML as a string
     */
    public static String documentToXmlString(Document document) {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString();
        } catch (TransformerException e) {
            throw new RuntimeException("Could not serialize XML document to a string", e);
        }
    }

    /** Shape of a GeoJSON FeatureCollection - just the fields {@link #geojsonToXml} needs. */
    private static class GeoJsonFeatureCollection {
        List<GeoJsonFeature> features;
    }

    private static class GeoJsonFeature {
        GeoJsonGeometry geometry;
        GeoJsonProperties properties;
    }

    private static class GeoJsonGeometry {
        String type;
        // Shape depends on "type" - a Polygon's coordinates are nested rings of [x, y] points;
        // a Point's is a single flat [x, y] pair. Left as a raw JsonElement and parsed manually
        // per type (see extractPolygonVertices/extractPointVertex) rather than one fixed nested
        // list type, since Gson can't map one field to two different shapes.
        JsonElement coordinates;
    }

    private static class GeoJsonProperties {
        GeoJsonClassification classification;
    }

    private static class GeoJsonClassification {
        String name;
        List<Integer> color;
    }

    /**
     * Whether {@link #getCachedUsername()} last resolved a real username, as opposed to falling
     * back to {@code "<username not found>"}. Used to disable UI elements (e.g. "Open Repository
     * Import Dialog") until a real username is available, rather than letting them be used with
     * that placeholder text still showing.
     * @return the property - true once a real username has been resolved, false otherwise
     */
    public static BooleanProperty usernameResolvedProperty() {
        return usernameResolvedProperty;
    }
}


