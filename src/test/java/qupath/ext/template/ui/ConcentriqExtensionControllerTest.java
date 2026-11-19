package qupath.ext.template.ui;

import com.google.gson.Gson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import qupath.lib.common.ColorTools;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.interfaces.ROI;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Tier 1" unit tests: pure functions and Gson-mapped data classes that don't require network
 * access, a live QuPath GUI, or JavaFX to be running. Fixtures are trimmed versions of real API
 * responses seen during development, not synthetic guesses at the shape.
 */
class ConcentriqExtensionControllerTest {

    private static final Gson GSON = new Gson();

    // ------------------------------------------------------------------------------------
    // Gson-mapped response classes
    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("imageSets (repo) list response")
    class ImageSetsResponseTests {

        private static final String JSON = """
                {
                    "meta": {
                        "pagination": { "page": 1, "rowsPerPage": 100, "totalRows": 3 }
                    },
                    "data": {
                        "imageSets": [
                            {
                                "id": 4,
                                "name": "klg2160-repo",
                                "description": "",
                                "groupName": "klg2160-repo-group",
                                "thumbnailURL": "https://example.com/thumb.jpg"
                            },
                            {
                                "id": 5,
                                "name": "gm3167-repo",
                                "description": "",
                                "groupName": null,
                                "thumbnailURL": ""
                            }
                        ]
                    }
                }
                """;

        @Test
        @DisplayName("maps pagination fields")
        void mapsPagination() {
            var response = GSON.fromJson(JSON, ConcentriqExtensionController.ApiResponse.class);
            assertNotNull(response.meta);
            assertNotNull(response.meta.pagination);
            assertEquals(1, response.meta.pagination.page);
            assertEquals(100, response.meta.pagination.rowsPerPage);
            assertEquals(3, response.meta.pagination.totalRows);
        }

        @Test
        @DisplayName("maps each imageSet row, including a null groupName")
        void mapsImageSets() {
            var response = GSON.fromJson(JSON, ConcentriqExtensionController.ApiResponse.class);
            List<ConcentriqExtensionController.ImageSet> imageSets = response.data.imageSets;

            assertEquals(2, imageSets.size());

            var first = imageSets.get(0);
            assertEquals(4, first.id);
            assertEquals("klg2160-repo", first.name);
            assertEquals("klg2160-repo-group", first.groupName);

            var second = imageSets.get(1);
            assertEquals(5, second.id);
            assertNull(second.groupName, "groupName should be null for ungrouped repos, not throw or default to empty string");
        }
    }

    @Nested
    @DisplayName("images (per-repo image list) response")
    class ImagesResponseTests {

        private static final String JSON = """
                {
                    "data": {
                        "images": [
                            {
                                "id": 72443,
                                "name": "VZ304-Lung-RFP-R_134147.svs",
                                "slideName": "VZ304-Lung-RFP-R_134147.svs",
                                "mppx": 0.264175,
                                "mppy": 0.264175,
                                "imgWidth": 98319,
                                "imgHeight": 88091,
                                "objectivePower": 40,
                                "status": 2,
                                "thumbURL": {
                                    "type": "thumb",
                                    "signedURL": "https://example.com/thumb.jpg"
                                }
                            }
                        ]
                    }
                }
                """;

        @Test
        @DisplayName("maps image fields, including the nested thumbURL and status")
        void mapsImageRecord() {
            var response = GSON.fromJson(JSON, ConcentriqExtensionController.ImagesApiResponse.class);
            var image = response.data.images.get(0);

            assertEquals(72443, image.id);
            assertEquals("VZ304-Lung-RFP-R_134147.svs", image.name);
            assertEquals(98319, image.imgWidth);
            assertEquals(88091, image.imgHeight);
            assertEquals(0.264175, image.mppx, 1e-9);
            assertEquals(0.264175, image.mppy, 1e-9);
            assertEquals(40, image.objectivePower, 1e-9);
            assertEquals(2, image.status, "status is now used to skip not-yet-successful images in createProjectWithImages");
            assertNotNull(image.thumbURL);
            assertEquals("https://example.com/thumb.jpg", image.thumbURL.signedURL);
        }

        @Test
        @DisplayName("defaults mppx/mppy/objectivePower to 0.0 (not an error) when the key is absent")
        void missingCalibrationDefaultsToZero() {
            String jsonWithoutCalibration = """
                    { "data": { "images": [ { "id": 1, "name": "no-mpp.svs" } ] } }
                    """;
            var response = GSON.fromJson(jsonWithoutCalibration, ConcentriqExtensionController.ImagesApiResponse.class);
            var image = response.data.images.get(0);

            // This is exactly the case the mppx/mppy guard in createProjectWithImages exists for -
            // Gson leaves primitive doubles at 0.0 rather than null when the JSON key is missing.
            assertEquals(0.0, image.mppx);
            assertEquals(0.0, image.mppy);
            assertEquals(0.0, image.objectivePower);
        }
    }

    @Nested
    @DisplayName("GET /api/images/:id response (single image detail)")
    class ImageDetailResponseTests {

        private static final String BRIGHTFIELD_JSON = """
                {
                    "data": {
                        "id": 103093,
                        "name": "CMU-2.svs",
                        "imageData": {
                            "imageSources": {
                                "94117": {
                                    "id": 94117,
                                    "imageServerUrl": "https://tileserver.example.com/iiif/CMU-2.svs/info.json"
                                }
                            },
                            "fluorescenceChannels": null
                        }
                    }
                }
                """;

        // Trimmed to 2 of the 3 real channels for brevity - shape is identical either way.
        private static final String FLUORESCENCE_JSON = """
                {
                    "data": {
                        "id": 94186,
                        "name": "5290-14-MT3-GFAP - 202607072122.zip",
                        "imageData": {
                            "imageSources": {
                                "85071": {
                                    "id": 85071,
                                    "fluorescenceChannelId": 14754,
                                    "imageServerUrl": "https://tileserver.example.com/iiif/shared.ndpis/info.json"
                                },
                                "85072": {
                                    "id": 85072,
                                    "fluorescenceChannelId": 14755,
                                    "imageServerUrl": "https://tileserver.example.com/iiif/shared.ndpis/info.json"
                                }
                            },
                            "fluorescenceChannels": {
                                "14754": { "id": 14754, "channelName": "Dapi 378", "lut": "#2200ff" },
                                "14755": { "id": 14755, "channelName": "Fitc 474", "lut": "#12ff00" }
                            }
                        }
                    }
                }
                """;

        @Test
        @DisplayName("brightfield image has a null fluorescenceChannels map")
        void brightfieldHasNullFluorescenceChannels() {
            var response = GSON.fromJson(BRIGHTFIELD_JSON, ConcentriqExtensionController.ImageDetailResponse.class);

            assertNull(response.data.imageData.fluorescenceChannels,
                    "fluorescenceChannels should be null for a plain brightfield image");

            var source = response.data.imageData.imageSources.values().iterator().next();
            assertEquals("https://tileserver.example.com/iiif/CMU-2.svs/info.json", source.imageServerUrl);
        }

        @Test
        @DisplayName("fluorescence image has a populated fluorescenceChannels map")
        void fluorescenceHasPopulatedFluorescenceChannels() {
            var response = GSON.fromJson(FLUORESCENCE_JSON, ConcentriqExtensionController.ImageDetailResponse.class);

            assertNotNull(response.data.imageData.fluorescenceChannels);
            assertFalse(response.data.imageData.fluorescenceChannels.isEmpty(),
                    "fluorescenceChannels should be non-empty for a multi-channel image - this is exactly the " +
                            "condition fetchImageDetails() uses to set isFluorescence");
            assertEquals(2, response.data.imageData.imageSources.size(),
                    "one imageSources entry per channel");
        }
    }

    @Nested
    @DisplayName("IIIF info.json response")
    class InfoJsonResponseTests {

        private static final String JSON = """
                {
                    "@context": "http://iiif.io/api/image/2/context.json",
                    "@id": "https://tileserver.example.com/iiif/CMU-1.svs",
                    "protocol": "http://iiif.io/api/image",
                    "width": 46000,
                    "height": 32914,
                    "tiles": [
                        {
                            "width": 256,
                            "height": 256,
                            "scaleFactors": [1, 2, 4, 8, 16, 32, 64, 128, 256]
                        }
                    ]
                }
                """;

        @Test
        @DisplayName("maps width/height and the tile pyramid")
        void mapsWidthHeightAndTiles() {
            var info = GSON.fromJson(JSON, ConcentriqExtensionController.InfoJsonResponse.class);

            assertEquals(46000, info.width);
            assertEquals(32914, info.height);
            assertEquals(1, info.tiles.size());

            var tile = info.tiles.get(0);
            assertEquals(256, tile.width);
            assertEquals(256, tile.height);
            assertEquals(List.of(1, 2, 4, 8, 16, 32, 64, 128, 256), tile.scaleFactors);
        }
    }

    @Nested
    @DisplayName("IAS token response")
    class TokenResponseTests {

        @Test
        @DisplayName("maps the 'token' key")
        void mapsTokenKey() {
            var response = GSON.fromJson("{\"token\": \"abc123\"}", ConcentriqExtensionController.TokenResponse.class);
            assertEquals("abc123", response.token);
            assertNull(response.apiKey);
        }

        @Test
        @DisplayName("maps the 'apiKey' key (the integration guide's alternate shape)")
        void mapsApiKeyKey() {
            var response = GSON.fromJson("{\"apiKey\": \"xyz789\"}", ConcentriqExtensionController.TokenResponse.class);
            assertEquals("xyz789", response.apiKey);
            assertNull(response.token);
        }
    }

    @Nested
    @DisplayName("GET /api/annotations response")
    class AnnotationsResponseTests {

        private static final String JSON = """
                {
                    "data": {
                        "annotations": [
                            {
                                "id": 48058,
                                "text": "",
                                "shape": "rect",
                                "shapeString": "601.4493988548766 2931.423219820083 2668.4550969957736 3121.2050944981706",
                                "color": "#39FF14",
                                "isNegative": false,
                                "imageId": 103982,
                                "annotationClassId": null,
                                "annotationClassName": null
                            }
                        ]
                    }
                }
                """;

        @Test
        @DisplayName("maps annotation fields, including a null annotationClassName")
        void mapsAnnotationRecord() {
            var response = GSON.fromJson(JSON, ConcentriqExtensionController.AnnotationsApiResponse.class);
            var annotation = response.data.annotations.get(0);

            assertEquals(48058, annotation.id);
            assertEquals("rect", annotation.shape);
            assertEquals("601.4493988548766 2931.423219820083 2668.4550969957736 3121.2050944981706", annotation.shapeString);
            assertEquals("#39FF14", annotation.color);
            assertEquals(103982, annotation.imageId);
            assertFalse(annotation.isNegative);
            assertNull(annotation.annotationClassName);
        }
    }

    @Nested
    @DisplayName("annotationClasses response")
    class AnnotationClassesResponseTests {

        private static final String JSON = """
                {
                    "items": [
                        { "id": 3, "name": "Plasma cell", "color": "#00FF2A", "isArchived": false },
                        { "id": 999, "name": "Retired class", "color": "#FF0000", "isArchived": true }
                    ]
                }
                """;

        @Test
        @DisplayName("maps each class, including the isArchived flag")
        void mapsAnnotationClasses() {
            var response = GSON.fromJson(JSON, ConcentriqExtensionController.AnnotationClassesResponse.class);

            assertEquals(2, response.items.size());
            assertEquals("Plasma cell", response.items.get(0).name);
            assertFalse(response.items.get(0).isArchived);
            assertTrue(response.items.get(1).isArchived);
        }
    }

    // ------------------------------------------------------------------------------------
    // parseHexColor
    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("parseHexColor")
    class ParseHexColorTests {

        @Test
        @DisplayName("parses a hex string with a leading #")
        void parsesWithHash() {
            Integer expected = ColorTools.packRGB(0x00, 0xFF, 0x2A);
            assertEquals(expected, ConcentriqExtensionController.parseHexColor("#00FF2A"));
        }

        @Test
        @DisplayName("parses a hex string without a leading #")
        void parsesWithoutHash() {
            Integer expected = ColorTools.packRGB(0x00, 0xFF, 0x2A);
            assertEquals(expected, ConcentriqExtensionController.parseHexColor("00FF2A"));
        }

        @Test
        @DisplayName("returns null for a null input")
        void nullInput() {
            assertNull(ConcentriqExtensionController.parseHexColor(null));
        }

        @Test
        @DisplayName("returns null for a blank input")
        void blankInput() {
            assertNull(ConcentriqExtensionController.parseHexColor("   "));
        }

        @Test
        @DisplayName("returns null (not an exception) for a too-short string")
        void tooShort() {
            assertNull(ConcentriqExtensionController.parseHexColor("#0000"));
        }

        @Test
        @DisplayName("returns null (not an exception) for non-hex characters")
        void nonHexCharacters() {
            assertNull(ConcentriqExtensionController.parseHexColor("#GGGGGG"));
        }
    }

    // ------------------------------------------------------------------------------------
    // toPathObjects
    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("toPathObjects")
    class ToPathObjectsTests {

        // Deliberately non-square, so it's possible to tell whether x/width and y/height are
        // each being scaled by the correct respective image dimension (see
        // documentsWidthHeightScalingIssue() below - as of this version, they are not).
        private static final int TEST_WIDTH = 2000;
        private static final int TEST_HEIGHT = 1000;
        private static final ImageServerMetadata TEST_METADATA = new ImageServerMetadata.Builder(
                ConcentriqExtensionController.ConcentriqTileServer.class, "test", TEST_WIDTH, TEST_HEIGHT)
                .build();

        private ConcentriqExtensionController.AnnotationRecord rectAnnotation(
                int id, String shapeString, String color, String className, String text) {
            var annotation = new ConcentriqExtensionController.AnnotationRecord();
            annotation.id = id;
            annotation.shape = "rect";
            annotation.shapeString = shapeString;
            annotation.color = color;
            annotation.annotationClassName = className;
            annotation.text = text;
            return annotation;
        }

        private ConcentriqExtensionController.AnnotationRecord freeAnnotation(int id, String shapeString) {
            var annotation = new ConcentriqExtensionController.AnnotationRecord();
            annotation.id = id;
            annotation.shape = "free";
            annotation.shapeString = shapeString;
            return annotation;
        }

        private ConcentriqExtensionController.AnnotationRecord ellipseAnnotation(int id, String shapeString) {
            var annotation = new ConcentriqExtensionController.AnnotationRecord();
            annotation.id = id;
            annotation.shape = "ellipse";
            annotation.shapeString = shapeString;
            return annotation;
        }

        private ConcentriqExtensionController.AnnotationRecord pinAnnotation(int id, String shapeString) {
            var annotation = new ConcentriqExtensionController.AnnotationRecord();
            annotation.id = id;
            annotation.shape = "pin";
            annotation.shapeString = shapeString;
            return annotation;
        }

        private ConcentriqExtensionController.AnnotationRecord arrowAnnotation(int id, String shapeString) {
            var annotation = new ConcentriqExtensionController.AnnotationRecord();
            annotation.id = id;
            annotation.shape = "arrow";
            annotation.shapeString = shapeString;
            return annotation;
        }

        private ConcentriqExtensionController.AnnotationRecord rulerAnnotation(int id, String shapeString) {
            var annotation = new ConcentriqExtensionController.AnnotationRecord();
            annotation.id = id;
            annotation.shape = "ruler";
            annotation.shapeString = shapeString;
            return annotation;
        }

        @Test
        @DisplayName("converts a valid rect annotation, scaling shapeString's normalized (0-10000) values by image size")
        void convertsValidRect() {
            // shapeString is now [x, y, width, height] on a 0-10000 normalized scale, converted
            // to pixels using the image's actual dimensions - not raw pixel values directly.
            var annotation = rectAnnotation(1, "1000 2000 500 500", null, null, null);

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertEquals(1, result.size());
            ROI roi = result.get(0).getROI();
            assertEquals(200.0, roi.getBoundsX(), 1e-6);
            assertEquals(100.0, roi.getBoundsWidth(), 1e-6);
            // y and height come out as 400/100 here, not the 200/50 you'd get by scaling with
            // TEST_HEIGHT directly - see documentsWidthHeightScalingIssue() for why.
            assertEquals(400.0, roi.getBoundsY(), 1e-6);
            assertEquals(100.0, roi.getBoundsHeight(), 1e-6);
        }

        @Test
        @DisplayName("tags every converted object with concentriq_annotation_id, regardless of shape")
        void setsConcentriqAnnotationIdOnEveryShape() {
            var rect = rectAnnotation(101, "0 0 1000 1000", null, null, null);
            var ellipse = ellipseAnnotation(102, "0 0 1000 1000");
            var free = freeAnnotation(103, "1000,2000 3000,2000 2000,4000");
            var pin = pinAnnotation(104, "1000 2000");
            var arrow = arrowAnnotation(105, "1000 2000 3000 4000");
            var ruler = rulerAnnotation(106, "1000 2000 3000 4000");

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(
                    List.of(rect, ellipse, free, pin, arrow, ruler), TEST_METADATA);

            assertEquals(6, result.size());
            assertEquals("101", result.get(0).getMetadata().get("concentriq_annotation_id"));
            assertEquals("102", result.get(1).getMetadata().get("concentriq_annotation_id"));
            assertEquals("103", result.get(2).getMetadata().get("concentriq_annotation_id"));
            assertEquals("104", result.get(3).getMetadata().get("concentriq_annotation_id"));
            assertEquals("105", result.get(4).getMetadata().get("concentriq_annotation_id"));
            assertEquals("106", result.get(5).getMetadata().get("concentriq_annotation_id"));
        }

        @Test
        @DisplayName("FLAG: y/height currently scale using imageWidth, not imageHeight, for non-square images")
        void documentsWidthHeightScalingIssue() {
            // toPathObjects computes aspectRatio = imageHeight / imageWidth, then divides y and
            // height by (10000 * aspectRatio). Substituting that in:
            //   y = (parts[1] * imageHeight) / (10000 * (imageHeight / imageWidth))
            //     = parts[1] * imageWidth / 10000
            // The imageHeight terms cancel out algebraically, leaving y (and height) scaled by
            // imageWidth instead of imageHeight. For a non-square image this looks like an
            // unintended bug, not a deliberate design choice - this test documents the current,
            // real behavior rather than asserting it's correct.
            var annotation = rectAnnotation(2, "0 5000 100 1000", null, null, null);

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);
            ROI roi = result.get(0).getROI();

            double yScaledByWidthActual = 5000.0 * TEST_WIDTH / 10000.0;
            double yScaledByHeightIfFixed = 5000.0 * TEST_HEIGHT / 10000.0;
            double heightScaledByWidthActual = 1000.0 * TEST_WIDTH / 10000.0;
            double heightScaledByHeightIfFixed = 1000.0 * TEST_HEIGHT / 10000.0;

            assertEquals(yScaledByWidthActual, roi.getBoundsY(), 1e-6,
                    "current behavior: y is scaled by imageWidth due to the aspectRatio cancellation");
            assertNotEquals(yScaledByHeightIfFixed, roi.getBoundsY(), 1e-6,
                    "if this assertion starts failing, the scaling issue may have been fixed - " +
                            "this test (and its @DisplayName) should be revisited at that point");

            assertEquals(heightScaledByWidthActual, roi.getBoundsHeight(), 1e-6,
                    "height has the same scaling issue as y, for the same algebraic reason");
            assertNotEquals(heightScaledByHeightIfFixed, roi.getBoundsHeight(), 1e-6);
        }

        // @Test
        // @DisplayName("skips an annotation with an unsupported shape rather than throwing")
        // void skipsUnsupportedShape() {
        //     var annotation = new ConcentriqExtensionController.AnnotationRecord();
        //     annotation.id = 3;
        //     annotation.shape = "ellipse"; // not yet handled
        //     annotation.shapeString = "1000 1000 500 500";

        //     List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

        //     assertTrue(result.isEmpty());
        // }

        @Test
        @DisplayName("skips a rect annotation whose shapeString doesn't have exactly 4 numbers")
        void skipsWrongNumberCount() {
            var annotation = rectAnnotation(4, "1000 1000 500", null, null, null);

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("skips a rect annotation with a non-numeric shapeString")
        void skipsNonNumericShapeString() {
            var annotation = rectAnnotation(5, "a b c d", null, null, null);

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("converts a valid 'free' (polygon) annotation, scaling each vertex the same way as 'rect'")
        void convertsValidFreeShape() {
            // Triangle at normalized (1000,2000) (3000,2000) (2000,4000). For this test image
            // (2000x1000), both axes happen to scale by the same 0.2 factor - see
            // documentsWidthHeightScalingIssue() for why - giving pixel points
            // (200,400) (600,400) (400,800).
            var annotation = freeAnnotation(12, "1000,2000 3000,2000 2000,4000");

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertEquals(1, result.size());
            ROI roi = result.get(0).getROI();
            assertEquals(3, roi.getAllPoints().size(), "should have one vertex per input point");
            assertEquals(200.0, roi.getBoundsX(), 1e-6);
            assertEquals(400.0, roi.getBoundsY(), 1e-6);
            assertEquals(400.0, roi.getBoundsWidth(), 1e-6);
            assertEquals(400.0, roi.getBoundsHeight(), 1e-6);
        }

        @Test
        @DisplayName("skips a 'free' annotation with fewer than 3 points")
        void skipsFreeShapeWithTooFewPoints() {
            var annotation = freeAnnotation(13, "1000,2000 3000,2000");

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("skips a 'free' annotation with a malformed point (missing comma)")
        void skipsFreeShapeWithMalformedPoint() {
            var annotation = freeAnnotation(14, "1000,2000 3000 2000,4000");

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("skips a 'free' annotation with a non-numeric point")
        void skipsFreeShapeWithNonNumericPoint() {
            var annotation = freeAnnotation(15, "1000,2000 a,b 2000,4000");

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("converts a valid ellipse annotation, sharing rect's box parsing but producing a smaller (elliptical) area")
        void convertsValidEllipse() {
            // Same shapeString as convertsValidRect() - same bounding box (200,400,100,100) in
            // pixels - but shape="ellipse" should inscribe an ellipse in that box, not a rectangle.
            var annotation = ellipseAnnotation(16, "1000 2000 500 500");

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertEquals(1, result.size());
            ROI roi = result.get(0).getROI();
            assertEquals(200.0, roi.getBoundsX(), 1e-6);
            assertEquals(400.0, roi.getBoundsY(), 1e-6);
            assertEquals(100.0, roi.getBoundsWidth(), 1e-6);
            assertEquals(100.0, roi.getBoundsHeight(), 1e-6);

            // An ellipse inscribed in a 100x100 box has area pi/4 * 100 * 100 =~ 7854, not the
            // full 10000 a rectangle would have - confirming the ellipse factory was really used.
            double expectedEllipseArea = Math.PI / 4.0 * 100.0 * 100.0;
            assertEquals(expectedEllipseArea, roi.getArea(), 1.0);
        }

        @Test
        @DisplayName("skips an ellipse annotation whose shapeString doesn't have exactly 4 numbers")
        void skipsEllipseWrongNumberCount() {
            var annotation = ellipseAnnotation(17, "1000 1000 500");

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("skips an ellipse annotation with a non-numeric shapeString")
        void skipsEllipseNonNumericShapeString() {
            var annotation = ellipseAnnotation(18, "a b c d");

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("converts a valid pin annotation into a single-point ROI with zero area")
        void convertsValidPin() {
            // Same "1000 2000" x/y pair as convertsValidRect()'s first two values - same
            // (200,400) pixel point, but as a standalone point rather than a box corner.
            var annotation = pinAnnotation(19, "1000 2000");

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertEquals(1, result.size());
            ROI roi = result.get(0).getROI();
            assertEquals(1, roi.getAllPoints().size(), "a pin should be a single-point ROI");
            assertEquals(200.0, roi.getBoundsX(), 1e-6);
            assertEquals(400.0, roi.getBoundsY(), 1e-6);
            assertEquals(0.0, roi.getBoundsWidth(), 1e-6);
            assertEquals(0.0, roi.getBoundsHeight(), 1e-6);
            assertEquals(0.0, roi.getArea(), 1e-6, "a point has no area - matches the real API's size: 0");
        }

        @Test
        @DisplayName("skips a pin annotation whose shapeString doesn't have exactly 2 numbers")
        void skipsPinWrongNumberCount() {
            var annotation = pinAnnotation(20, "1000 2000 3000");

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("skips a pin annotation with a non-numeric shapeString")
        void skipsPinNonNumericShapeString() {
            var annotation = pinAnnotation(21, "a b");

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("converts a valid arrow annotation into a line ROI with the arrowhead always set to end-facing")
        void convertsValidArrow() {
            // (1000,2000) -> (3000,4000) normalized, giving pixel points (200,400) -> (600,800).
            var annotation = arrowAnnotation(22, "1000 2000 3000 4000");

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertEquals(1, result.size());
            PathObject pathObject = result.get(0);
            ROI roi = pathObject.getROI();
            assertEquals(2, roi.getAllPoints().size(), "a line/arrow should have exactly 2 points");
            assertEquals(200.0, roi.getBoundsX(), 1e-6);
            assertEquals(400.0, roi.getBoundsY(), 1e-6);
            assertEquals(400.0, roi.getBoundsWidth(), 1e-6);
            assertEquals(400.0, roi.getBoundsHeight(), 1e-6);

            assertEquals(">", pathObject.getMetadata().get("arrowhead"),
                    "arrowhead direction is always set to '>' (end), regardless of Concentriq's data");
        }

        @Test
        @DisplayName("skips an arrow annotation whose shapeString doesn't have exactly 4 numbers")
        void skipsArrowWrongNumberCount() {
            var annotation = arrowAnnotation(23, "1000 1000 500");

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("skips an arrow annotation with a non-numeric shapeString")
        void skipsArrowNonNumericShapeString() {
            var annotation = arrowAnnotation(24, "a b c d");

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("converts a valid ruler annotation into a line ROI tagged with associated_concentriq_shape")
        void convertsValidRuler() {
            // Same endpoints as convertsValidArrow() - same underlying geometry - but "ruler"
            // should get a distinguishing tag instead of an arrowhead.
            var annotation = rulerAnnotation(25, "1000 2000 3000 4000");

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertEquals(1, result.size());
            PathObject pathObject = result.get(0);
            ROI roi = pathObject.getROI();
            assertEquals(2, roi.getAllPoints().size(), "a ruler should have exactly 2 points, same as a line");
            assertEquals(200.0, roi.getBoundsX(), 1e-6);
            assertEquals(400.0, roi.getBoundsY(), 1e-6);
            assertEquals(400.0, roi.getBoundsWidth(), 1e-6);
            assertEquals(400.0, roi.getBoundsHeight(), 1e-6);

            assertEquals("ruler", pathObject.getMetadata().get("associated_concentriq_shape"));
            assertNull(pathObject.getMetadata().get("arrowhead"), "a ruler should not get an arrowhead tag");
        }

        @Test
        @DisplayName("skips a ruler annotation whose shapeString doesn't have exactly 4 numbers")
        void skipsRulerWrongNumberCount() {
            var annotation = rulerAnnotation(26, "1000 1000 500");

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("skips a ruler annotation with a non-numeric shapeString")
        void skipsRulerNonNumericShapeString() {
            var annotation = rulerAnnotation(27, "a b c d");

            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("reuses an already-interned PathClass instance by name, rather than creating a new one")
        void reusesInternedPathClass() {
            // Simulates a class already registered for the project via fetchAnnotationClasses().
            PathClass existing = PathClass.getInstance("Plasma cell", ColorTools.packRGB(0, 255, 42));

            var annotation = rectAnnotation(6, "0 0 1000 1000", null, "Plasma cell", null);
            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertSame(existing, result.get(0).getPathClass(),
                    "PathClass.getInstance(name) should return the same interned instance, " +
                            "not a new differently-colored one under the same name");
        }

        @Test
        @DisplayName("leaves an annotation unclassified when annotationClassName is null")
        void unclassifiedWhenNameIsNull() {
            var annotation = rectAnnotation(7, "0 0 1000 1000", null, null, null);
            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertNull(result.get(0).getPathClass());
        }

        @Test
        @DisplayName("sets the per-object color when present")
        void setsColorWhenPresent() {
            var annotation = rectAnnotation(8, "0 0 1000 1000", "#39FF14", null, null);
            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            Integer expected = ConcentriqExtensionController.parseHexColor("#39FF14");
            assertEquals(expected, result.get(0).getColor());
        }

        @Test
        @DisplayName("leaves color unset (null) when the annotation has none")
        void leavesColorNullWhenAbsent() {
            var annotation = rectAnnotation(9, "0 0 1000 1000", null, null, null);
            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            assertNull(result.get(0).getColor());
        }


        @Test
        @DisplayName("does not set a description for blank/empty text (matching the real API's \"\" default)")
        void leavesDescriptionUnsetForBlankText() {
            var annotation = rectAnnotation(11, "0 0 1000 1000", null, null, "");
            List<PathObject> result = ConcentriqExtensionController.toPathObjects(List.of(annotation), TEST_METADATA);

            PathObject pathObject = result.get(0);
            assertInstanceOf(PathAnnotationObject.class, pathObject);
            assertNull(((PathAnnotationObject) pathObject).getDescription());
        }
    }
}