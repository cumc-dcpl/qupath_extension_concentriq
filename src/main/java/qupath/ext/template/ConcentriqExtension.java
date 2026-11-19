package qupath.ext.template;


import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.Property;
import javafx.beans.property.StringProperty;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.template.ui.EditConfigurationModalController;
import qupath.ext.template.ui.ConcentriqExtensionController;
import qupath.ext.template.ui.ConcentriqExtensionController.ConcentriqServerBuilder;
import qupath.ext.template.ui.ConcentriqExtensionController.ConcentriqTileServer;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.io.GsonTools;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.common.ColorTools;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.roi.EllipseROI;
import qupath.lib.roi.GeometryROI;
import qupath.lib.roi.LineROI;
import qupath.lib.roi.PointsROI;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.PolylineROI;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.geom.Point2;
import qupath.lib.extension.svg.SvgTools;
import qupath.lib.regions.ImageRegion;
import java.awt.image.BufferedImage;
import org.locationtech.jts.geom.Geometry;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
 
import java.io.IOException;
import java.util.ResourceBundle;


/**
 * This QuPath extension allows for a seamless integration between with Proscia Concentriq LS.
 * <p>
 * The Concentriq-QuPath extension allows users to seamlessly leverage QuPath’s open and versatile
 * ecosystem with Concentriq LS, the image management and storage platform.
 * Users no longer need to download and separately manage local versions of their images.
 * </p>
 * <p>
 * This software is provided 'as is' and 'as available' with all faults and without warranty of any kind.
 * The DCPL expressly disclaims all warranties, whether express, implied, or statutory,
 * including but not limited to implied warranties of merchantability, fitness for a particular purpose,
 * and non-infringement.
 * </p>
 */
public class ConcentriqExtension implements QuPathExtension {
	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.template.ui.strings");
	private static final Logger logger = LoggerFactory.getLogger(ConcentriqExtension.class);

	private static final String EXTENSION_NAME = resources.getString("name");

	private static final String EXTENSION_DESCRIPTION = resources.getString("description");

	private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.5.0");

	private boolean isInstalled = false;

	private static final BooleanProperty enableExtensionProperty = PathPrefs.createPersistentPreference(
			"enableExtension", true);

	private static final Property<String> concentriqAPIKey = PathPrefs.createPersistentPreference(
			"demo.num.option", "");

	private static final Property<String> concentriqUrl = PathPrefs.createPersistentPreference(
			"concentriq.url.option", "https://cumc-dcpl.concentriq.proscia.com/");

	private static final Property<String> analysisRequestorFieldKey = PathPrefs.createPersistentPreference(
			"concentriq.num.analysisSenderFieldKey", "qupath_analysis_sender");

	private static final Property<String> analysisResultsAppId = PathPrefs.createPersistentPreference(
			"concentriq.num.analysisResultAppId", "");

	public static Property<String> concentriqAPIKeyProperty() {
		return concentriqAPIKey;
	}

	public static Property<String> concentriqUrlProperty() {
		return concentriqUrl;
	}

	public static Property<String> analysisRequestorFieldKeyProperty() {
		return analysisRequestorFieldKey;
	}

	public static Property<String> analysisResultsAppIdProperty() {
		return analysisResultsAppId;
	}

	private static final BooleanProperty concentriqBackedProjectProperty = new SimpleBooleanProperty(false);

	private static final BooleanProperty environmentMismatchProperty = new SimpleBooleanProperty(false);

	private Stage stage;
	private Stage imageViewerStage;
	private Stage urlSetterStage;

	/**
	 * Set to true for the duration of this extension's own hierarchy-replacing operations
	 * ({@code refresh()}, and the post-export re-fetch in {@code exportAnnotationsToConcentriq()}),
	 * so {@link #annotationChangeListener} can tell those apart from genuine local edits and skip
	 * marking anything dirty while they run.
	 */
	private boolean isSyncingFromConcentriq = false;

	private static final double NEW_ANNOTATION_GEOMETRIC_MATCH_TOLERANCE = 0.01;

	private static final String FALLBACK_MARKUP_LAYER_COLOR = "#FF0000";

	private static String packedRgbToHex(Integer packedRgb) {
		if (packedRgb == null) {
			return FALLBACK_MARKUP_LAYER_COLOR;
		}
		int[] rgb = ColorTools.unpackRGB(packedRgb);
		return String.format("#%02X%02X%02X", rgb[0], rgb[1], rgb[2]);
	}

	/**
	 * Marks Concentriq-managed annotations as needing to be written back on the next export,
	 * whenever their classification/shape/etc. changes locally in QuPath.
	 * <p>
	 * Only reacts to settled content changes on existing objects ({@code !isChanging()} and
	 * {@code !isStructureChangeEvent()}) - {@code addObjects}/{@code removeObjects} (used by
	 * {@code refresh()} and the export flow's own post-export re-fetch) fire ADDED/REMOVED-type
	 * events instead, via separate factory methods, so this listener never reacts to the
	 * extension's own hierarchy replacements, only to genuine local edits.
	 * <p>
	 * Annotations without {@code concentriq_annotation_id} are skipped - they're not yet
	 * Concentriq's to manage, so a change to them doesn't need a "synced" flag; their need to be
	 * exported is already fully captured by that key's absence.
	 */
	private final PathObjectHierarchyListener annotationChangeListener = event -> {
		logger.info(event.toString());
		if (event.isChanging() || isSyncingFromConcentriq) {
			return;
		}
		for (PathObject annotation : event.getChangedObjects()) {
			if (annotation.getMetadata().containsKey("concentriq_annotation_id")) {
				annotation.getMetadata().put("synced", "false");
			}
		}
	};

	@Override
	public void installExtension(QuPathGUI qupath) {
		if (isInstalled) {
			logger.debug("{} is already installed", getName());
			return;
		}
		isInstalled = true;

		// QuPath installs every
		// discovered extension in turn during startup, in an undefined
		// order, and an uncaught exception from any one extension's installExtension() risks
		// interrupting that loop before it reaches extensions that would have been installed
		// afterward - matching a real, historical QuPath issue ("Silent crash upon start when
		// loading a broken extension"). Logging here, rather than letting anything propagate,
		// ensures this extension can never take others down with it.
		try {
			addPreferenceToPane(qupath);
			registerConcentriqServerBuilder();

			// The main title bar's Stage.titleProperty() is bound (once, in QuPathGUI's own
			// constructor - confirmed there's no other .bind() call anywhere else in that class, so
			// this is safe to take over permanently rather than risk being silently overridden
			// later) to QuPath's own internal title logic. Unbind it here so it can be set directly,
			// then take over updating it ourselves - appending the currently-configured Concentriq
			// URL after whatever QuPath would otherwise show.
			qupath.getStage().titleProperty().unbind();
			updateWindowTitle(qupath, qupath.getImageData());

			// Keep the title in sync with the URL itself, independent of image changes - otherwise
			// changing the URL via "Set Concentriq API Url" wouldn't be reflected until the next
			// time the active image happened to change (which might be never, in the same session).
			// The cached username is also cleared here - a different URL could mean a genuinely
			// different organization/user, so the next title update should re-look it up rather
			// than keep showing whichever username was cached before the change.
			concentriqUrlProperty().addListener((observable, oldValue, newValue) -> {
				ConcentriqExtensionController.clearCachedUsername();
				updateWindowTitle(qupath, qupath.getImageData());

				Project<BufferedImage> currentProject = qupath.getProject();
				if (currentProject == null) {
					environmentMismatchProperty.set(false);
					return;
				}
				String projectEnvironment = currentProject.getMetadata().get(ConcentriqExtensionController.TARGET_ENVIRONMENT_METADATA_KEY);
				if (projectEnvironment == null) {
					environmentMismatchProperty.set(false);
					return;
				}
				boolean mismatch = environmentMismatch(projectEnvironment, newValue);
				environmentMismatchProperty.set(mismatch);
				if (mismatch) {
					logger.warn("Concentriq URL changed to '{}', which does not match the currently open project's target environment '{}' - disabling project-dependent menu items until this is resolved.",
							newValue, projectEnvironment);
					Dialogs.showWarningNotification("Environment mismatch",
							"The entered Concentriq URL does not match the\n environment this project was created for\n (" + projectEnvironment
									+ ").\n Project-dependent menu items are disabled until the\n URL matches, or a matching project is opened.");
				}
			});

			// Same reasoning, for the API key: changing it via "Set Concentriq API Key" should be
			// reflected in the title right away too, not just on the next image change.
			concentriqAPIKeyProperty().addListener((observable, oldValue, newValue) -> {
				ConcentriqExtensionController.clearCachedUsername();
				updateWindowTitle(qupath, qupath.getImageData());
			});

			try {
				addMenuItem(qupath, "Edit Concentriq Configuration", this::createConfigurationEditor);
				MenuItem repositoryImportMenuItem = addMenuItem(qupath, "Open Repository Import Dialog", this::createImageViewer);
				// Disabled until a real username has actually been resolved - otherwise this
				// could be used while the title bar (and any other username-dependent state)
				// still shows the "<username not found>" placeholder.
				repositoryImportMenuItem.disableProperty().bind(
						enableExtensionProperty.not().or(ConcentriqExtensionController.usernameResolvedProperty().not()));

				MenuItem exportMenuItem = addMenuItem(qupath, "Export Annotations to Concentriq", this::exportAnnotationsToConcentriq);
				// Only meaningful when an image is actually open - disabled otherwise, in addition
				// to the usual enableExtensionProperty condition every menu item already respects.
				exportMenuItem.disableProperty().bind(
						enableExtensionProperty.not().or(qupath.imageDataProperty().isNull())
								.or(ConcentriqExtensionController.usernameResolvedProperty().not())
								.or(environmentMismatchProperty)
								.or(concentriqBackedProjectProperty.not()));

				MenuItem exportAnalysisResultsMenuItem = addMenuItem(qupath, "Export Analysis Results to Concentriq", this::exportAnalysisResultsToConcentriq);
				// Also requires a currently-open, Concentriq-backed image.
				exportAnalysisResultsMenuItem.disableProperty().bind(
						enableExtensionProperty.not().or(qupath.imageDataProperty().isNull())
								.or(ConcentriqExtensionController.usernameResolvedProperty().not())
								.or(((StringProperty) analysisRequestorFieldKeyProperty()).isEmpty())
								.or(((StringProperty) analysisResultsAppIdProperty()).isEmpty())
								.or(environmentMismatchProperty)
								.or(concentriqBackedProjectProperty.not()));
				
				MenuItem refreshMenuItem = addMenuItem(qupath, "Refresh", this::refresh);
				// Only meaningful when an image is actually open - disabled otherwise, in addition
				// to the usual enableExtensionProperty condition every menu item already respects.
				refreshMenuItem.disableProperty().bind(
						enableExtensionProperty.not().or(qupath.projectProperty().isNull())
							.or(ConcentriqExtensionController.usernameResolvedProperty().not())
							.or(environmentMismatchProperty)
							.or(concentriqBackedProjectProperty.not()));
			}
			catch(Exception e) {
				logger.error("Error setting up menu item {}.", getName(), e);
			}
	
			// Detect when the active image changes, and specifically when it's one of ours (backed
			// by ConcentriqTileServer) rather than some unrelated image the user opened directly.
			// This is the intended hook for fetching that image's annotations from Concentriq once
			// it's actually being displayed - see onConcentriqImageOpened(int, ImageData).
			qupath.imageDataProperty().addListener((observable, oldValue, newValue) -> {
				// Each image has its own PathObjectHierarchy instance - detach from the previous
				// one (if any) before attaching to the new one, rather than accumulating listeners
				// on hierarchies nobody's looking at anymore.
				if (oldValue != null) {
					oldValue.getHierarchy().removeListener(annotationChangeListener);
				}

				updateWindowTitle(qupath, newValue);

				if (newValue == null) {
					return;
				}

				// Guard against the user changing the configured Concentriq URL via "Edit
				// Concentriq Configuration" *after* a project was already successfully opened,
				// then opening an image within it.
				Project<BufferedImage> currentProject = qupath.getProject();
				if (currentProject != null) {
					String projectEnvironment = currentProject.getMetadata().get(ConcentriqExtensionController.TARGET_ENVIRONMENT_METADATA_KEY);
					if (projectEnvironment != null && environmentMismatch(projectEnvironment, concentriqUrlProperty().getValue())) {
						String currentEnvironment = concentriqUrlProperty().getValue();
						logger.warn("Currently open project '{}' was created against Concentriq environment '{}', but the extension is now configured for '{}' - closing the image that was just opened.",
								currentProject.getName(), projectEnvironment, currentEnvironment);
						// Deferred, for the same reason as the projectProperty listener below: we're
						// currently nested inside whatever call opened this image, and mutating the
						// viewer's image synchronously from here would race its remaining work.
						Platform.runLater(() -> {
							qupath.getViewer().resetImageData();
							Dialogs.showErrorMessage("Environment mismatch",
									"The currently open project was created\nagainst a different Concentriq environment \n(" + projectEnvironment
											+ ") than the\n one currently configured \n(" + currentEnvironment
											+ "). \nPlease update the Concentriq URL in the extension \nconfiguration, or open a project created for this environment.");
						});
						return;
					}
				}

				newValue.getHierarchy().addListener(annotationChangeListener);
				try {
					var server = newValue.getServer();
					if (server instanceof ConcentriqTileServer concentriqServer) {
						onConcentriqImageOpened(concentriqServer.getImageId(), newValue);
					}
				} catch (Exception e) {
					logger.debug("Could not check whether the newly opened image is Concentriq-backed", e);
				}
			});

			qupath.projectProperty().addListener((observable, oldValue, newProject) -> {
				concentriqBackedProjectProperty.set(newProject != null
						&& newProject.getMetadata().get(ConcentriqExtensionController.CONCENTRIQ_REPO_ID_METADATA_KEY) != null);

				if (newProject == null) {
					return;
				}
				String projectEnvironment = newProject.getMetadata().get(ConcentriqExtensionController.TARGET_ENVIRONMENT_METADATA_KEY);
				if (projectEnvironment == null) {
					return;
				}
				String currentEnvironment = concentriqUrlProperty().getValue();
				if (environmentMismatch(projectEnvironment, currentEnvironment)) {
					logger.warn("Project '{}' was created against Concentriq environment '{}', but the extension is currently configured for '{}' - refusing to open it.",
							newProject.getName(), projectEnvironment, currentEnvironment);
					// Deferred rather than called synchronously here: this listener is firing
					// *during* QuPath's own setProject(newProject) call, which still has work left
					// to do after it finishes notifying listeners. Calling qupath.setProject(null)
					// synchronously from here would be a reentrant call nested inside that still-
					// in-progress outer call - the outer call's own remaining logic (and its final
					// "Project set to {}" log, using its original argument) would then run *after*
					// our nested null-out, clobbering it and leaving the mismatched project active
					// again. Running this on the next pulse instead lets the outer call finish first.
					Platform.runLater(() -> {
						qupath.setProject(null);
						Dialogs.showErrorMessage("Environment mismatch",
								"This project was created against \na different Concentriq environment\n (" + projectEnvironment
										+ ") than the one\n currently configured (" + currentEnvironment
										+ ").\n Please update the Concentriq URL in the extension\n configuration, or open a project created for this environment.");
					});
				}
			});
			Project<BufferedImage> alreadyOpenProject = qupath.getProject();
			concentriqBackedProjectProperty.set(alreadyOpenProject != null
					&& alreadyOpenProject.getMetadata().get(ConcentriqExtensionController.CONCENTRIQ_REPO_ID_METADATA_KEY) != null);

		} catch (Exception e) {
			logger.error("Error installing {} - some or all of its functionality may be unavailable this session", getName(), e);
		}
	}

	private static boolean environmentMismatch(String projectEnvironment, String currentEnvironment) {
		return !ConcentriqExtensionController.stripAllTrailingChars(projectEnvironment, '/')
				.equalsIgnoreCase(ConcentriqExtensionController.stripAllTrailingChars(currentEnvironment, '/'));
	}

	/**
	 * Update the main QuPath window's title to append the current user's username and the
	 * currently-configured Concentriq URL after whatever QuPath would otherwise show -
	 * {@code "QuPath - <image name> - <username>@<url, no scheme>"}, or
	 * {@code "QuPath - <username>@<url, no scheme>"} when no image is open.
	 * <p>
	 * The username comes from {@link ConcentriqExtensionController#getCachedUsername()},
	 * itself cached for the whole session after its first lookup. The URL's {@code https://}/{@code http://} scheme
	 * is stripped for display only - the underlying property value (used for actual API calls
	 * elsewhere) is untouched.
	 * <p>
	 * Requires {@code qupath.getStage().titleProperty()} to have already been unbound (see
	 * {@link #installExtension}) - it's normally bound to QuPath's own internal title logic,
	 * which would otherwise throw when set directly.
	 * @param qupath the QuPath GUI instance
	 * @param imageData the newly-active image's data, or null if no image is currently open
	 */
	private void updateWindowTitle(QuPathGUI qupath, ImageData<BufferedImage> imageData) {
		String baseTitle = imageData == null
				? "QuPath"
				: "QuPath - " + qupath.getDisplayedImageName(imageData);
		String urlWithoutScheme = concentriqUrlProperty().getValue().replaceFirst("^https?://", "");
		String username = ConcentriqExtensionController.getCachedUsername();
		qupath.getStage().setTitle(baseTitle + " - " + username + "@" + urlWithoutScheme);
	}


    /**
	 * Called when a Concentriq-backed image becomes the active image in QuPath.
	 * <p>
	 * Fetches that image's annotations from Concentriq and converts them to {@link PathObject}s
	 * (on a background thread, since this is called from a JavaFX property listener on the
	 * Application Thread and both steps can involve blocking network calls), then adds them to
	 * the image's hierarchy back on the JavaFX Application Thread via {@link Platform#runLater}.
	 * <p>
	 * {@link PathObjectHierarchy#addObjects(java.util.Collection)} already fires a hierarchy
	 * changed event internally when it makes changes, so no separate manual refresh call is
	 * needed here.
	 * @param imageId the Concentriq image id that was just opened
	 * @param imageData the newly opened image's data, whose hierarchy the annotations are added to
	 */
	private void onConcentriqImageOpened(int imageId, ImageData<BufferedImage> imageData) {
		ImageServerMetadata metadata = imageData.getServer().getMetadata();
		CompletableFuture.supplyAsync(() -> ConcentriqExtensionController.fetchAnnotations(imageId))
				.thenApply((annotations) -> ConcentriqExtensionController.toPathObjects(annotations, metadata))
				.whenComplete((pathObjects, error) -> Platform.runLater(() -> {
					if (error != null) {
						logger.error("Failed to fetch or convert annotations for Concentriq image {}", imageId, error);
						return;
					}
					imageData.getHierarchy().addObjects(pathObjects);
					logger.info("Added {} annotation(s) for Concentriq image {}", pathObjects.size(), imageId);
				}));
	}

	private void addPreferenceToPane(QuPathGUI qupath) {
        var propertyItem = new PropertyItemBuilder<>(enableExtensionProperty, Boolean.class)
				.name(resources.getString("menu.enable"))
				.category("Concentriq extension")
				.description("Enable the QuPath <> Concentriq extension")
				.build();
		qupath.getPreferencePane()
				.getPropertySheet()
				.getItems()
				.add(propertyItem);
	}


	private MenuItem addMenuItem(QuPathGUI qupath, String menuItemText, Callable<Void> clickAction)  {
		var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
		MenuItem menuItem = new MenuItem(menuItemText);
		if(clickAction != null) {
			try {
				menuItem.setOnAction(e ->  {
					try {
						clickAction.call();
					}
					catch(Exception err) {
						logger.error("Error running menu action '{}'", menuItemText, err);
    					Dialogs.showErrorMessage("Error", menuItemText + " failed: " + err.getMessage());
					}
				});
			}
			catch(Exception e) {
				logger.error("Error setting up menu action '{}'", menuItemText, e);
			}
		}
		menuItem.disableProperty().bind(enableExtensionProperty.not());
		menu.getItems().add(menuItem);
		return menuItem;
	}

	private Void createConfigurationEditor() throws Exception {
		if (stage == null) {
			try {
				stage = new Stage();
				Scene scene = new Scene(EditConfigurationModalController.createInstance());
				stage.initOwner(QuPathGUI.getInstance().getStage());
				stage.setTitle(resources.getString("stage.title"));
				stage.setScene(scene);
				stage.setResizable(true);
			} catch (IOException e) {
				Dialogs.showErrorMessage(resources.getString("error"), resources.getString("error.gui-loading-failed"));
				logger.error("Unable to load extension interface FXML", e);
			}
		}
		stage.show();
		return null;
	}

	private Void createImageViewer() throws Exception {
		if (imageViewerStage == null) {
			try {
				imageViewerStage = new Stage();
				Scene scene = new Scene(ConcentriqExtensionController.createInstance());
				imageViewerStage.initOwner(QuPathGUI.getInstance().getStage());
				imageViewerStage.setTitle(resources.getString("import_view.title"));
				imageViewerStage.setScene(scene);
				imageViewerStage.setResizable(true);
			} catch (IOException e) {
				Dialogs.showErrorMessage(resources.getString("error"), resources.getString("error.gui-loading-failed") + e.toString());
				logger.error("Unable to load extension interface FXML", e);
			}
		}
		imageViewerStage.show();
		return null;
	}


	/**
	 * Refresh the current image's Concentriq-managed annotations - fetches every annotation
	 * Concentriq has for this image, removes whichever currently-present annotations are already
	 * tagged with {@code concentriq_annotation_id} (i.e. previously fetched from Concentriq), and
	 * adds the freshly-fetched replacements. Annotations without that tag (drawn locally in
	 * QuPath, never yet synced) are left completely untouched - they aren't Concentriq's to
	 * manage, so a refresh has no reason to touch them.
	 * <p>
	 * The removal only happens once the fetch has actually succeeded, so a failed refresh leaves
	 * the existing annotations in place rather than wiping them out with nothing to replace them.
	 */
	private Void refresh() throws Exception {
		var qupath = QuPathGUI.getInstance();
		var imageData = qupath.getImageData();

		if (imageData != null) {
			int unsyncedCount = 0;
			for (PathObject annotation : imageData.getHierarchy().getAnnotationObjects()) {
				if (annotation.getMetadata().containsKey("concentriq_annotation_id")
						&& "false".equals(annotation.getMetadata().get("synced"))) {
					unsyncedCount++;
				}
			}
			if (unsyncedCount > 0) {
				String message = unsyncedCount == 1
						? "1 annotation has an unsynced change that will be lost if you continue. Continue anyway?"
						: unsyncedCount + " annotations have unsynced changes that will be lost if you continue. Continue anyway?";
				boolean confirmed = Dialogs.showConfirmDialog("Unsynced changes found", message);
				if (!confirmed) {
					logger.info("User declined to refresh - {} annotation(s) have unsynced changes", unsyncedCount);
					return null;
				}
			}
		}

		// Annotation-refresh is only run if an image is currently selected, this
		// no longer exits the whole method via an early return, since the new-images check below
		// should still run regardless of whether any image is open at all (the refresh button
		// itself is now enabled as soon as a project is open, not only when an image is open).
		if (imageData == null) {
			logger.info("No image is currently open - skipping annotation refresh");
		} else if (!(imageData.getServer() instanceof ConcentriqTileServer concentriqServer)) {
			logger.info("Current image is not Concentriq-backed - skipping annotation refresh");
		} else {
			int imageId = concentriqServer.getImageId();
			ImageServerMetadata metadata = imageData.getServer().getMetadata();

			logger.info("Refreshing annotations for Concentriq image {}", imageId);

			CompletableFuture.supplyAsync(() -> ConcentriqExtensionController.fetchAnnotations(imageId))
					.thenApply((annotations) -> ConcentriqExtensionController.toPathObjects(annotations, metadata))
					.whenComplete((freshAnnotations, error) -> Platform.runLater(() -> {

						logger.info("Beginning process for refreshing annotations");
						if (error != null) {
							logger.error("Failed to refresh annotations for Concentriq image {}", imageId, error);
							return;
						}

						var hierarchy = imageData.getHierarchy();
						hierarchy.resolveHierarchy();

						List<PathObject> managedAnnotations = new ArrayList<>();
						// Captured here, before removeObjects runs below, since
						// removeObjects(..., false) deletes a removed object's children
						// outright - capturing first (copying into a new list, since
						// getChildObjects() may return a live/unmodifiable view that could be
						// affected by the subsequent removal) lets any tile/detection children
						// be reattached to the fresh annotation sharing the same Concentriq id
						// afterward.
						Map<Integer, List<PathObject>> childrenByAnnotationId = new HashMap<>();
						for (PathObject annotation : hierarchy.getAnnotationObjects()) {
							if (annotation.getMetadata().containsKey("concentriq_annotation_id")) {
								managedAnnotations.add(annotation);
								int annotationId = Integer.parseInt(annotation.getMetadata().get("concentriq_annotation_id"));
								childrenByAnnotationId.put(annotationId, new ArrayList<>(annotation.getChildObjects()));
							}
						}
						logger.info("Storing {} children for reattachment", childrenByAnnotationId);
						isSyncingFromConcentriq = true;
						try {
							hierarchy.removeObjects(managedAnnotations, false);
							hierarchy.addObjects(freshAnnotations);

							// Reattached unconditionally. If an annotation was deleted in Concentriq since the last
							// refresh (so its id no longer appears among freshAnnotations at
							// all), its old children are simply never reattached here.
							for (PathObject freshAnnotation : freshAnnotations) {
								String freshAnnotationIdString = freshAnnotation.getMetadata().get("concentriq_annotation_id");
								if (freshAnnotationIdString == null) {
									continue;
								}
								List<PathObject> oldChildren = childrenByAnnotationId.get(Integer.parseInt(freshAnnotationIdString));
								if (oldChildren != null && !oldChildren.isEmpty()) {
									freshAnnotation.addChildObjects(oldChildren);
									freshAnnotation.setLocked(true);
								}
							}
						} finally {
							isSyncingFromConcentriq = false;
						}

						logger.info("Refreshed {} Concentriq-managed annotation(s) for image {} ({} other annotation(s) left unchanged)",
								freshAnnotations.size(), imageId, hierarchy.getAnnotationObjects().size() - freshAnnotations.size());
						Dialogs.showInfoNotification("Success", "Refreshed annotations from Concentriq.");
					}));
		}

		// Also check for new images in the currently-viewed image's repository
		Project<BufferedImage> project = qupath.getProject();
		if (project == null) {
			logger.warn("No project is currently open - skipping new-images check");
			return null;
		}

		String repoIdString = project.getMetadata().get(ConcentriqExtensionController.CONCENTRIQ_REPO_ID_METADATA_KEY);
		if (repoIdString == null) {
			logger.warn("Project '{}' has no stored Concentriq repo id - skipping new-images check", project.getName());
			Dialogs.showWarningNotification("Warning",
					"This project has no stored Concentriq repo\n id, so it can't be checked for new images.");
			return null;
		}
		int repoId = Integer.parseInt(repoIdString);

		CompletableFuture.supplyAsync(() -> ConcentriqExtensionController.fetchImagesForRepo(repoId))
				.whenComplete((freshImages, error) -> Platform.runLater(() -> {
					if (error != null) {
						logger.error("Failed to fetch images for repo {}", repoId, error);
						return;
					}

					Set<Integer> existingImageIds = new HashSet<>();
					for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
						if (entry.getServerBuilder() instanceof ConcentriqExtensionController.ConcentriqServerBuilder concentriqBuilder) {
							existingImageIds.add(concentriqBuilder.getImageId());
						}
					}

					int addedCount = 0;
					for (ConcentriqExtensionController.ImageRecord image : freshImages) {
						if (existingImageIds.contains(image.id)) {
							continue;
						}
						try {
							if (ConcentriqExtensionController.addImageToProject(project, image)) {
								addedCount++;
							}
						} catch (IOException e) {
							logger.error("Failed to add new image {} to project '{}'", image.id, project.getName(), e);
						}
					}

					if (addedCount > 0) {
						try {
							project.syncChanges();
						} catch (IOException e) {
							logger.error("Failed to sync project '{}' after adding new images", project.getName(), e);
							return;
						}
						qupath.refreshProject();
						logger.info("Added {} new image(s) to project '{}' for repo {}", addedCount, project.getName(), repoId);
						Dialogs.showInfoNotification("Success", "Added " + addedCount + " new image(s) from Concentriq.");
					} else {
						logger.info("No new images found for repo {}", repoId);
						Dialogs.showInfoNotification("Success", "No new images found.");
					}
				}));

		return null;
	}


	/**
	 * Resolve a dirty annotation's current QuPath classification into the Concentriq
	 * annotationClassId to send back in its PATCH request.
	 * <p>
	 * Returns null - meaning the field should be omitted from the PATCH entirely,
	 * both when the annotation is currently unclassified in QuPath, and when its classification
	 * name has no match in {@code classIdsByName}.
	 * @param annotation the annotation being exported
	 * @param classIdsByName the batch-fetched lookup from {@link ConcentriqExtensionController#fetchAnnotationClassIdsByName()}
	 * @return the resolved Concentriq annotation class id, or null if it should be omitted
	 */
	private Integer resolveConcentriqAnnotationClassId(PathObject annotation, Map<String, Integer> classIdsByName) {
		String classNameToResolve;
		if (PathClass.StandardPathClasses.IGNORE.equals(annotation.getPathClass())) {
			classNameToResolve = annotation.getMetadata().get("concentriq_original_annotation_class");
			if (classNameToResolve == null || classNameToResolve.isEmpty()) {
				return null; // originally unclassified in Concentriq - omit the field, same as any other unclassified annotation
			}
		} else {
			PathClass pathClass = annotation.getPathClass();
			if (pathClass == null) {
				return null; // unclassified in QuPath - omit the field entirely
			}
			classNameToResolve = pathClass.getName();
		}
		Integer classId = classIdsByName.get(classNameToResolve.toLowerCase());
		if (classId == null) {
			logger.warn("No matching Concentriq annotation class found for '{}' - leaving annotationClassId unset for this update", classNameToResolve);
		}
		return classId;
	}


	private String resolveAnnotationText(PathObject annotation) {
		return annotation.getName() != null ? annotation.getName() : "";
	}


	public record CellNucleusRoi(List<Point2> points) {}


	private Void exportAnalysisResultsToConcentriq() throws Exception {
		var qupath = QuPathGUI.getInstance();
		var imageData = qupath.getImageData();
		if (imageData == null) {
			logger.warn("No image is currently open - nothing to export");
			return null;
		}

		if (!(imageData.getServer() instanceof ConcentriqTileServer concentriqServer)) {
			logger.warn("Current image is not Concentriq-backed - nothing to export");
			return null;
		}

		PathObject selectedAnnotation = imageData.getHierarchy().getSelectionModel().getSelectedObject();
		if (selectedAnnotation == null || !selectedAnnotation.isAnnotation()) {
			logger.warn("No annotation is currently selected - cannot export analysis results");
			Dialogs.showErrorMessage(resources.getString("error"), "Please select an annotation before attempting to export analysis results.");
			return null;
		}

		int imageId = concentriqServer.getImageId();

		String iasToken = ConcentriqExtensionController.fetchIasToken(imageId);
		if (iasToken == null) {
			logger.warn("Could not fetch IAS auth token for image {} - cannot export analysis results", imageId);
			return null;
		}

		logger.info("Succesfully fetched ias token for image");

		ConcentriqExtensionController.ImageDetails imageDetails = ConcentriqExtensionController.fetchImageDetails(imageId);
		if (imageDetails == null || imageDetails.storageKey == null) {
			logger.warn("Could not fetch storage key for image {} - cannot export analysis results", imageId);
			return null;
		}

		int imageWidth = imageData.getServer().getMetadata().getWidth();
		int imageHeight = imageData.getServer().getMetadata().getHeight();
		double mppx = imageData.getServer().getMetadata().getPixelWidthMicrons();
		double mppy = imageData.getServer().getMetadata().getPixelHeightMicrons();
		String username = ConcentriqExtensionController.getCachedUsername();
		Integer analysisId = ConcentriqExtensionController.createConcentriqAnalysis(imageId, imageDetails.storageKey, imageWidth, imageHeight, mppx, mppy, username, iasToken);
		if (analysisId == null) {
			logger.warn("Failed to submit analysis results for image {}", imageId);
			return null;
		}

		logger.info("Succesfully created an analysis in processing state: {}", analysisId);

		Map<PathClass, List<CellNucleusRoi>> cellNucleusRoisByClassification = new LinkedHashMap<>();
		for (PathObject child : selectedAnnotation.getChildObjects()) {
			if (!(child instanceof PathCellObject cell)) {
				continue;
			}
			if (!cell.hasNucleus()) {
				logger.warn("Cell {} has no nucleus ROI - skipping", cell);
				continue;
			}
			ROI nucleusRoi = cell.getNucleusROI();
			// Unclassified cells fall back to the selected annotation's own
			// classification. Only falls through to "unnamed" when neither the
			// cell nor its parent annotation has a classification.
			PathClass effectivePathClass = cell.getPathClass() != null ? cell.getPathClass() : selectedAnnotation.getPathClass();
			cellNucleusRoisByClassification
					.computeIfAbsent(effectivePathClass, key -> new ArrayList<>())
					.add(new CellNucleusRoi(nucleusRoi.getAllPoints()));
		}
		logger.info("Collected cell nucleus ROI(s) across {} distinct classification(s) from the selected annotation",
				cellNucleusRoisByClassification.size());

		int colorIndex = 0;
		for (Map.Entry<PathClass, List<CellNucleusRoi>> entry : cellNucleusRoisByClassification.entrySet()) {
			PathClass pathClass = entry.getKey();
			List<CellNucleusRoi> cellNucleusRois = entry.getValue();
			String layerName = pathClass != null ? pathClass.getName() : "unnamed";
			// Matches whatever color QuPath itself already assigns to cells of this
			// classification.
			String strokeColor = packedRgbToHex(pathClass != null ? pathClass.getColor() : null);

			Integer markupLayerId = ConcentriqExtensionController.createConcentriqMarkupLayer(analysisId, layerName, strokeColor, iasToken);
			if (markupLayerId == null) {
				logger.warn("Failed to create markup layer '{}' for analysis {}", layerName, analysisId);
				return null;
			}
			logger.info("Successfully created a markup layer '{}': {}", layerName, markupLayerId);

			boolean markupsCreated = ConcentriqExtensionController.createConcentriqMarkups(markupLayerId, cellNucleusRois, iasToken);
			if (!markupsCreated) {
				logger.warn("Failed to create markups for layer {} ('{}')", markupLayerId, layerName);
				return null;
			}
		}


		boolean markedComplete = ConcentriqExtensionController.markConcentriqAnalysisComplete(analysisId, iasToken);
		if (!markedComplete) {
			logger.warn("Failed to mark analysis {} as complete", analysisId);
			return null;
		}

		Dialogs.showInfoNotification("Success", "Successfully exported analysis results to Concentriq.");

		return null;
	}


	private Void exportAnnotationsToConcentriq() throws Exception {
		logger.info("Now exporting annotations to concentriq");

		var qupath = QuPathGUI.getInstance();
		var imageData = qupath.getImageData();
		if (imageData == null) {
			logger.warn("No image is currently open - nothing to export");
			return null;
		}

		var annotations = imageData.getHierarchy().getAnnotationObjects();
		logger.info(Integer.toString(annotations.size()));

		// New annotations were drawn locally in QuPath and have never been sent to/from
		// Concentriq - they lack the concentriq_annotation_id metadata tag that
		// ConcentriqExtensionController.toPathObjects() sets on every annotation fetched FROM
		// Concentriq. Anything carrying that tag already exists there.
		List<PathObject> newAnnotations = new ArrayList<>();
		List<PathObject> existingAnnotations = new ArrayList<>();
		for (PathObject annotation : annotations) {
			if (annotation.getMetadata().containsKey("concentriq_annotation_id")) {
				existingAnnotations.add(annotation);
			} else if (annotation.getROI() instanceof PolylineROI) {
				// Not natively supported in Concentriq.
				logger.warn("Annotation is a polyline - not supported in Concentriq, leaving unchanged and not exporting");
				Dialogs.showWarningNotification("Warning", "Polyline annotations not supported in Concentriq so annotation was not exported.");
			} else if (annotation.getROI() instanceof PointsROI && annotation.getROI().getAllPoints().size() > 1) {
				// Not natively supported in Concentriq. The single point case is supported however.
				logger.warn("Annotation is a Points ROI with more than one point - not supported in Concentriq, leaving unchanged and not exporting");
				Dialogs.showWarningNotification("Warning", "Points annotations with more than one point \nnot supported in Concentriq so annotation was not exported.");
			} else {
				newAnnotations.add(annotation);
			}
		}
		logger.info("{} new annotation(s), {} existing annotation(s)", newAnnotations.size(), existingAnnotations.size());
 
		// Captured here, before the export below ever runs, so any tile/detection children of
		// a brand-new (not-yet-tracked) annotation can later be matched to its own
		// Concentriq-assigned counterpart by geometry - keyed by PathObject reference rather
		// than any id, since none exists yet for these. Matching a new annotation's geometry this way is safe even
		// though it's approximate: its ROI genuinely cannot change between this point and the
		// re-fetch below, since no editing happens in between.
		Map<PathObject, List<PathObject>> childrenByNewAnnotation = new HashMap<>();
		for (PathObject annotation : newAnnotations) {
			childrenByNewAnnotation.put(annotation, new ArrayList<>(annotation.getChildObjects()));
		}

		for (PathObject annotation : existingAnnotations) {
			if (!"false".equals(annotation.getMetadata().get("synced"))) {
				continue; // not dirty
			}
			if (annotation.getROI() instanceof GeometryROI
					&& ConcentriqExtensionController.hasDisconnectedPolygonIslands(annotation.getROI())) {
				logger.warn("Annotation {} consists of multiple, disconnected top-level polygons - aborting the entire export batch",
						annotation.getMetadata().get("concentriq_annotation_id"));
				Dialogs.showErrorMessage(resources.getString("error"), "Annotations consisting of multiple, top-level polygons\n not supported for export to concentriq via the Concentriq\n extension.  Adjust annotations and try again.");
				return null;
			}
		}

		// QuPath's Ignore classification is reserved specifically for negative regions imported
		// as standalone annotations because they had no matching positive region in Concentriq
		// (see ConcentriqExtensionController.toPathObjects) - those carry the
		// concentriq_original_annotation_class metadata key, set at that same import time. An
		// Ignore-classified annotation WITHOUT that key means the user applied the Ignore class
		// themselves to something that was never such an excluded region - this
		// aborts the entire export batch with an error, rather than silently exporting it
		// incorrectly (or silently dropping the classification). Checked for new annotations too,
		// since a brand-new annotation can just as easily be mis-classified this
		// way before ever being exported for the first time.
		for (PathObject annotation : newAnnotations) {
			if (PathClass.StandardPathClasses.IGNORE.equals(annotation.getPathClass())
					&& !annotation.getMetadata().containsKey("concentriq_original_annotation_class")) {
				logger.warn("A new annotation has been classified using QuPath's Ignore class, but it was never an excluded " +
						"region from Concentriq - aborting the entire export batch");
				Dialogs.showErrorMessage(resources.getString("error"),
						"An annotation has been classified using QuPath's Ignore class, but it was not an excluded region from "
								+ "Concentriq. Please remove this classification and try again.");
				return null;
			}
		}
		for (PathObject annotation : existingAnnotations) {
			if (!"false".equals(annotation.getMetadata().get("synced"))) {
				continue; // not dirty
			}
			if (PathClass.StandardPathClasses.IGNORE.equals(annotation.getPathClass())
					&& !annotation.getMetadata().containsKey("concentriq_original_annotation_class")) {
				logger.warn("Annotation {} has been classified using QuPath's Ignore class, but it was never an excluded " +
						"region from Concentriq - aborting the entire export batch", annotation.getMetadata().get("concentriq_annotation_id"));
				Dialogs.showErrorMessage(resources.getString("error"),
						"An annotation has been classified using QuPath's Ignore class, but it was not an excluded region from "
								+ "Concentriq. Please remove this classification and try again.");
				return null;
			}
		}

		Integer concentriqImageId = (imageData.getServer() instanceof ConcentriqTileServer concentriqServer)
				? concentriqServer.getImageId()
				: null;

		// New annotations: export from QuPath as GeoJSON, convert to Concentriq's XML format,
		// then import via the API. Existing annotations get their own handling further below.
		if (newAnnotations.isEmpty()) {
			logger.info("No new annotations to import");
		} else if (concentriqImageId != null) {
			ByteArrayOutputStream geojsonOutput = new ByteArrayOutputStream();
			PathIO.exportObjectsAsGeoJSON(geojsonOutput, newAnnotations, PathIO.GeoJsonExportOptions.FEATURE_COLLECTION);
			String geojson = geojsonOutput.toString(StandardCharsets.UTF_8);

			var xmlDocument = ConcentriqExtensionController.geojsonToXml(geojson, false, newAnnotations);
			String xmlString = ConcentriqExtensionController.documentToXmlString(xmlDocument);

			String response = ConcentriqExtensionController.exportAnnotationsToConcentriqInXml(concentriqImageId, xmlString);
			if (response != null) {
				logger.info("Imported {} new annotation(s) for Concentriq image {}", newAnnotations.size(), concentriqImageId);
			} else {
				logger.error("Failed to import new annotations for Concentriq image {}", concentriqImageId);
			}
		} else {
			// Shouldn't normally happen - the export menu item is disabled unless an image is
			// open, and onConcentriqImageOpened only ever fires for Concentriq-backed images -
			// but a user could still export from some other, non-Concentriq image.
			logger.warn("Current image is not Concentriq-backed - cannot import new annotations");
		}

		if (concentriqImageId == null) {
			Dialogs.showInfoNotification("Success", "Successfully exported annotations to Concentriq.");
			return null;
		}

		ImageServerMetadata metadata = imageData.getServer().getMetadata();

		Map<String, Integer> concentriqAnnotationClassIdsByName = ConcentriqExtensionController.fetchAnnotationClassIdsByName();

		// Existing annotations: for now, only polygon-, point-, rectangle-, ellipse-, and
		// line-shaped ones marked dirty ("synced" == "false") get written back, via a direct
		// shapeString update - not a full re-import. Classification (annotationClassId) is now
		// also synced for these (and for GeometryROI's own parent - see below - but not its
		// negative regions). Other existing-annotation changes aren't yet handled; they're
		// simply left dirty for a future export.
		int imageWidth = metadata.getWidth();
		for (PathObject annotation : existingAnnotations) {
			if (!"false".equals(annotation.getMetadata().get("synced"))) {
				continue; // not dirty
			}

			ROI roi = annotation.getROI();

			// Detect whether this annotation's shape TYPE has changed since it was last pulled
			// from Concentriq (e.g. a rectangle brush/wand-edited into a polygon or GeometryROI)
			// - checked FIRST, before any shape-specific branch below, so a mismatch is caught
			// regardless of which branch would otherwise claim it.
			if (roi instanceof GeometryROI) {
				if (!annotation.getMetadata().containsKey("contained_negative_concentriq_regions")) {
					// Became a GeometryROI without ever being a tracked negative-region parent
					// (e.g. a rect/polygon/etc. that got brush- or wand-edited) - needs its own
					// handling, not yet built. For now, just log and leave dirty.
					logger.info("Annotation {} (originally '{}') has unexpectedly become a GeometryROI without being a tracked negative-region parent - not yet supported for export, leaving marked as dirty",
							annotation.getMetadata().get("concentriq_annotation_id"), annotation.getMetadata().get("concentriq_shape"));
					continue;
				}
				// else: genuine, tracked negative-region parent - falls through to the existing
				// GeometryROI branch below.
			} else {
				String concentriqShape = annotation.getMetadata().get("concentriq_shape");
				Class<? extends ROI> expectedRoiClass = ConcentriqExtensionController.expectedRoiClassForConcentriqShape(concentriqShape);
				if (expectedRoiClass != null && !expectedRoiClass.isInstance(roi)) {
					String message = String.format("Annotation %s changed shape type from %s to %s - not supported for export", annotation.getMetadata().get("concentriq_annotation_id"),
							expectedRoiClass.getSimpleName(), roi.getClass().getSimpleName());
					logger.info(message);
					Dialogs.showInfoNotification("Unsupported Shape Change", message);
					continue;
				}
			}

			if (roi instanceof GeometryROI) {
				logger.info("Looking at a geometry");
				String containedNegativeRegionsJson = annotation.getMetadata().get("contained_negative_concentriq_regions");
				if (containedNegativeRegionsJson == null) {
					logger.info("exit early due to no contained negative regions json");
					continue; // no negative regions recorded for this annotation - left dirty
				}

				int parentAnnotationId = Integer.parseInt(annotation.getMetadata().get("concentriq_annotation_id"));

				// Concentriq stores the parent as its own, separate annotation - not the same as
				// what QuPath currently shows, since QuPath displays the already-excluded
				// combined result. Fetch the parent's own current (pre-edit) shapeString fresh,
				// rather than deriving it from the edited QuPath ROI.
				String originalParentShapeString = ConcentriqExtensionController.fetchAnnotationShapeString(parentAnnotationId);
				logger.info("the original parent (positive annotation):" + originalParentShapeString);
				if (originalParentShapeString == null) {
					logger.error("Failed to fetch original shapeString for Concentriq annotation {} - leaving marked as dirty", parentAnnotationId);
					continue;
				}

				List<Integer> negativeRegionIds = ConcentriqExtensionController.parseContainedNegativeRegionIds(containedNegativeRegionsJson);
				List<String> negativeRegionShapeStrings = new ArrayList<>();
				boolean fetchFailed = false;
				for (Integer negativeRegionId : negativeRegionIds) {
					String negativeRegionShapeString = ConcentriqExtensionController.fetchAnnotationShapeString(negativeRegionId);
					if (negativeRegionShapeString == null) {
						logger.error("Failed to fetch shapeString for negative region {} - leaving parent {} marked as dirty",
								negativeRegionId, parentAnnotationId);
						fetchFailed = true;
						break;
					}
					negativeRegionShapeStrings.add(negativeRegionShapeString);
				}
				if (fetchFailed) {
					logger.info("in fetchFailed branch");
					continue;
				}

				String newParentShapeString = ConcentriqExtensionController.computeUpdatedGeometryRoiShapeString(
						roi, originalParentShapeString, negativeRegionShapeStrings, imageWidth, metadata.getHeight());
				if (newParentShapeString == null) {
					logger.info("exit due to newParentShapeString null");
					continue; // computation failed - already logged, leave dirty
				}

				// Each negative region also needs updating, to stay consistent with the
				// parent's current shape - holes are extracted directly from the edited,
				// current geometry and assigned POSITIONALLY across the full set of associated
				// negative-region ids, with no attempt to match a specific hole to a specific
				// original negative region's own id. An arbitrary assignment is safe as
				// long as the COUNT matches - if it doesn't (a hole was filled in, or the
				// boundary split), the mismatch is left dirty with a warning.
				List<String> holeShapeStrings = ConcentriqExtensionController.extractHoleShapeStrings(roi, imageWidth);
				if (holeShapeStrings.size() != negativeRegionIds.size()) {
					logger.warn("Annotation {} has {} current hole(s) but {} associated negative region(s) - " +
									"leaving marked as dirty", parentAnnotationId, holeShapeStrings.size(), negativeRegionIds.size());
					Dialogs.showWarningNotification("Warning",
							"The annotation with id " + parentAnnotationId + "\n was left dirty because the number of its "
									+ "\nholes no longer matches its associated negative regions.");
					continue;
				}

				Map<Integer, String> newNegativeRegionShapeStrings = new HashMap<>();
				for (int i = 0; i < negativeRegionIds.size(); i++) {
					newNegativeRegionShapeStrings.put(negativeRegionIds.get(i), holeShapeStrings.get(i));
				}

				Integer parentAnnotationClassId = resolveConcentriqAnnotationClassId(annotation, concentriqAnnotationClassIdsByName);
				String parentText = resolveAnnotationText(annotation);
				String response = ConcentriqExtensionController.updateAnnotationShapeString(parentAnnotationId, newParentShapeString, parentAnnotationClassId, parentText);
				if (response == null) {
					logger.error("Failed to update shape for Concentriq annotation {} - leaving marked as dirty", parentAnnotationId);
					continue;
				}

				boolean allNegativeRegionsUpdated = true;
				for (Map.Entry<Integer, String> entry : newNegativeRegionShapeStrings.entrySet()) {
					// Negative regions don't get their classification or text synced.
					String negativeResponse = ConcentriqExtensionController.updateAnnotationShapeString(entry.getKey(), entry.getValue(), null, null);
					if (negativeResponse == null) {
						logger.error("Failed to update shape for negative region {} (parent {}) - leaving parent marked as dirty",
								entry.getKey(), parentAnnotationId);
						allNegativeRegionsUpdated = false;
					}
				}

				if (allNegativeRegionsUpdated) {
					annotation.getMetadata().remove("synced");
					logger.info("Updated shape for Concentriq annotation {} and its {} negative region(s)",
							parentAnnotationId, newNegativeRegionShapeStrings.size());
				}
				continue;
			}

			String shapeString;
			if (roi instanceof PolygonROI) {
				shapeString = ConcentriqExtensionController.polygonRoiToShapeString(roi, imageWidth);
			} else if (roi instanceof PointsROI) {
				shapeString = ConcentriqExtensionController.pointsRoiToShapeString(roi, imageWidth);
			} else if (roi instanceof RectangleROI) {
				shapeString = ConcentriqExtensionController.rectangleRoiToShapeString(roi, imageWidth);
			} else if (roi instanceof EllipseROI) {
				shapeString = ConcentriqExtensionController.ellipseRoiToShapeString(roi, imageWidth);
			} else if (roi instanceof LineROI) {
				shapeString = ConcentriqExtensionController.lineRoiToShapeString(roi, imageWidth);
			} else {
				continue; // only polygon, points, rectangle, ellipse, and line shapes are handled for now - left dirty otherwise
			}
			if (shapeString == null) {
				continue; // conversion failed (e.g. an empty points ROI) - already logged, leave dirty
			}

			logger.info("found a dirty, cq-managed annotation to export");

			int annotationId = Integer.parseInt(annotation.getMetadata().get("concentriq_annotation_id"));
			Integer annotationClassId = resolveConcentriqAnnotationClassId(annotation, concentriqAnnotationClassIdsByName);
			String annotationText = resolveAnnotationText(annotation);

			logger.info("new shape string:" + shapeString);

			String response = ConcentriqExtensionController.updateAnnotationShapeString(annotationId, shapeString, annotationClassId, annotationText);
			if (response != null) {
				annotation.getMetadata().remove("synced");
				logger.info("Updated shape for Concentriq annotation {}", annotationId);
			} else {
				logger.error("Failed to update shape for Concentriq annotation {} - leaving marked as dirty", annotationId);
			}
		}

		// After exporting, re-fetch this image's full annotation set from Concentriq and replace
		// everything currently in QuPath with it, so QuPath's view matches what Concentriq now
		// authoritatively has - including real ids for annotations that were just imported above.
		// Unlike refresh() (which only replaces already-managed annotations, leaving local-only
		// ones alone), this replaces the entire hierarchy's annotations, since by this point any
		// local annotations should have just been imported and are expected to now exist there too.
		int imageId = concentriqImageId;

		CompletableFuture.supplyAsync(() -> ConcentriqExtensionController.fetchAnnotations(imageId))
				.thenApply((fetchedAnnotations) -> ConcentriqExtensionController.toPathObjects(fetchedAnnotations, metadata))
				.whenComplete((freshAnnotations, error) -> Platform.runLater(() -> {
					if (error != null) {
						logger.error("Failed to re-fetch annotations after export for Concentriq image {}", imageId, error);
						Dialogs.showErrorMessage("Error", "Exported annotations, but failed to refresh from Concentriq.");
						return;
					}

					var hierarchy = imageData.getHierarchy();

					// Some analysis tools add result objects (e.g. tiles) as flat, top-level
					// objects rather than properly nesting them as children of the annotation
					// they geometrically belong under - they still render correctly in the
					// viewer either way (rendering is purely geometric), but getChildObjects()
					// below would incorrectly return empty for them unless the hierarchy's
					// parent-child relationships are first resolved here.
					hierarchy.resolveHierarchy();

					List<PathObject> currentAnnotations = new ArrayList<>(hierarchy.getAnnotationObjects());
					// Captured here so that any tile/detection children can be reattached to the fresh
					// annotation sharing the same Concentriq id afterward. Only applies to
					// annotations that are already Concentriq-tracked.
					Map<Integer, List<PathObject>> childrenByAnnotationId = new HashMap<>();
					for (PathObject annotation : currentAnnotations) {
						String annotationIdString = annotation.getMetadata().get("concentriq_annotation_id");
						if (annotationIdString != null) {
							childrenByAnnotationId.put(Integer.parseInt(annotationIdString), new ArrayList<>(annotation.getChildObjects()));
						}
					}
					isSyncingFromConcentriq = true;
					try {
						hierarchy.removeObjects(currentAnnotations, false);
						hierarchy.addObjects(freshAnnotations);

						// Reattached unconditionally, with no geometric validation against the
						// fresh annotation's own (possibly different) boundary. If
						// an annotation gets deleted in concentriq, exporting annotations / refreshing will cause it's
						// old children to be lost along with it.
						for (PathObject freshAnnotation : freshAnnotations) {
							List<PathObject> oldChildren = null;

							String freshAnnotationIdString = freshAnnotation.getMetadata().get("concentriq_annotation_id");
							if (freshAnnotationIdString != null) {
								oldChildren = childrenByAnnotationId.get(Integer.parseInt(freshAnnotationIdString));
							}

							// Falls back to geometric matching against the brand-new
							// annotations captured earlier - this covers a new annotation that
							// was just imported in this same export, so its fresh counterpart
							// now has a real id, but didn't have one yet when
							// childrenByAnnotationId was built above. Matched via
							// symmetric-difference area rather than an exact comparison, since
							// the shapeString round-trip can introduce rounding noise and
							// doesn't preserve vertex order.
							if (oldChildren == null) {
								Geometry freshGeometry = GeometryTools.roiToGeometry(freshAnnotation.getROI());
								for (Map.Entry<PathObject, List<PathObject>> entry : childrenByNewAnnotation.entrySet()) {
									if (entry.getValue().isEmpty()) {
										continue;
									}
									Geometry newAnnotationGeometry = GeometryTools.roiToGeometry(entry.getKey().getROI());
									double relativeDifference = freshGeometry.symDifference(newAnnotationGeometry).getArea() / freshGeometry.getArea();
									if (relativeDifference < NEW_ANNOTATION_GEOMETRIC_MATCH_TOLERANCE) {
										oldChildren = entry.getValue();
										break;
									}
								}
							}

							if (oldChildren != null && !oldChildren.isEmpty()) {
								freshAnnotation.addChildObjects(oldChildren);
								freshAnnotation.setLocked(true);
							}
						}
					} finally {
						isSyncingFromConcentriq = false;
					}

					logger.info("Replaced QuPath's annotations with {} annotation(s) fetched from Concentriq image {}",
							freshAnnotations.size(), imageId);
					Dialogs.showInfoNotification("Success", "Successfully exported annotations to Concentriq.");
				}));

		return null;
	}

    /**
	 * Register {@link ConcentriqServerBuilder} with QuPath's polymorphic Gson type adapter for
	 * {@link ImageServerBuilder.ServerBuilder} subtypes, so that projects containing
	 * Concentriq-backed image entries can be serialized (on save) and deserialized (on open).
	 * <p>
	 * This is deliberately done here in {@code installExtension}, which QuPath guarantees to
	 * call once at startup for every registered extension - rather than in a static initializer
	 * on {@code ConcentriqExtensionController}, which would only run whenever that specific
	 * class first happens to get loaded (e.g. only after the user opens the Image Viewer pane).
	 * Registering too late means opening a previously-created project (with no Image Viewer pane
	 * opened yet in that session) would fail to deserialize, since Gson wouldn't yet know how to
	 * handle the {@code ConcentriqServerBuilder} entries within it.
	 */
	private void registerConcentriqServerBuilder() {
		Object factory = ImageServers.getServerBuilderFactory();
		if (factory instanceof GsonTools.SubTypeAdapterFactory) {
			@SuppressWarnings("unchecked")
			GsonTools.SubTypeAdapterFactory<ImageServerBuilder.ServerBuilder> serverBuilderFactory =
					(GsonTools.SubTypeAdapterFactory<ImageServerBuilder.ServerBuilder>) factory;
			serverBuilderFactory.registerSubtype(ConcentriqServerBuilder.class);
		} else {
			logger.warn("Unable to register ConcentriqServerBuilder for project serialization - " +
					"unexpected factory type {}", factory == null ? "null" : factory.getClass());
		}
	}


	@Override
	public String getName() {
		return EXTENSION_NAME;
	}

	@Override
	public String getDescription() {
		return EXTENSION_DESCRIPTION;
	}
	
	@Override
	public Version getQuPathVersion() {
		return EXTENSION_QUPATH_VERSION;
	}
}
