package qupath.ext.template.ui;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Spinner;
import javafx.scene.layout.VBox;
import qupath.ext.template.ConcentriqExtension;
import qupath.fx.dialogs.Dialogs;

import java.io.IOException;
import java.util.ResourceBundle;
import javafx.scene.control.TextField;
import java.util.function.UnaryOperator;
import javafx.scene.control.TextFormatter;

/**
 * Controller for UI pane contained in interface.fxml
 */
public class EditConfigurationModalController extends VBox {
    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.template.ui.strings");

    @FXML
    private TextField concentriqAPIKey;

    @FXML
    private TextField concentriqUrl;

    @FXML
    private TextField qupathAnalysisSenderFieldKey;

    @FXML
    private TextField analysisExportAppId;

    /**
     * Create a new instance of the interface controller.
     * @return a new instance of the interface controller
     * @throws IOException If reading the extension FXML files fails.
     */
    public static EditConfigurationModalController createInstance() throws IOException {
        return new EditConfigurationModalController();
    }

    /**
     * This method reads an FXML file. These are markup files containing the structure of a UI element.
     * <p>
     * Fields in this class tagged with <code>@FXML</code> correspond to UI elements, and methods tagged with <code>@FXML</code> are methods triggered by actions on the UI (e.g., mouse clicks).
     * <p>
     * We consider the use of FXML to be "best practice" for UI creation, as it separates logic from layout and enables easier use of CSS. However, it is not mandatory, and you could instead define the layout of the UI using code.
     * @throws IOException If the FXML can't be read successfully.
     */
    private EditConfigurationModalController() throws IOException {
        var url = EditConfigurationModalController.class.getResource("edit_configuration_modal.fxml");
        FXMLLoader loader = new FXMLLoader(url, resources);
        loader.setRoot(this);
        loader.setController(this);
        loader.load();

        UnaryOperator<TextFormatter.Change> integerFilter = change -> {
            String newText = change.getControlNewText();
            if (newText.matches("\\d*")) {
                return change;
            }
            return null;
        };

        analysisExportAppId.setTextFormatter(new TextFormatter<>(integerFilter));

        // For extensions with a small number of options,
        // or with options that are very important for how the extension works,
        // it may be better to present them all to the user in the main extension GUI,
        // binding them to GUI elements, so they are updated when the user interacts with
        // the GUI, and so that the GUI elements are updated if the preference changes
        concentriqAPIKey.textProperty().bindBidirectional(ConcentriqExtension.concentriqAPIKeyProperty());
        concentriqAPIKey.textProperty().addListener((observableValue, oldValue, newValue) -> {
            Dialogs.showInfoNotification(
                    resources.getString("title"), "Concentriq API Key Updated Successfully");
        });

        concentriqUrl.textProperty().bindBidirectional(ConcentriqExtension.concentriqUrlProperty());
        concentriqUrl.textProperty().addListener((observableValue, oldValue, newValue) -> {
            Dialogs.showInfoNotification(
                    resources.getString("title"), "Concentriq API URL Updated Successfully");
        });

        qupathAnalysisSenderFieldKey.textProperty().bindBidirectional(ConcentriqExtension.analysisRequestorFieldKeyProperty());
        qupathAnalysisSenderFieldKey.textProperty().addListener((observableValue, oldValue, newValue) -> {
            Dialogs.showInfoNotification(
                    resources.getString("title"), "Analysis Results Sender Field Key Updated Successfully");
        });

        analysisExportAppId.textProperty().bindBidirectional(ConcentriqExtension.analysisResultsAppIdProperty());
        analysisExportAppId.textProperty().addListener((observableValue, oldValue, newValue) -> {
            Dialogs.showInfoNotification(
                    resources.getString("title"), "Exports Analysis App ID Updated Successfully");
        });
    }
}
